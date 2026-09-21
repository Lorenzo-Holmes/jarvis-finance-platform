package com.jarvis.research.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.service.SimRiskService;
import com.jarvis.research.service.SimTradeService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 风险检测执行器：按周期对<strong>任务所有者本人</strong>的模拟盘账户做一次风险体检。
 *
 * <p>参数（{@code params_json}）：</p>
 * <pre>
 * {
 *   "warnBelowPct": 25.0   // 可选，维持担保比例低于它就视为命中告警；缺省用 SimRiskService.WARN_MAINT_PCT
 * }
 * </pre>
 *
 * <p>⚠️ <strong>这个执行器只读，绝不强平</strong>。强平是系统级风控（{@code SimRiskService}
 * 每 30 秒扫描全部活跃账户）的职责，它属于平台而不是某一个用户。若这里改调
 * {@code SimRiskService.checkAndLiquidate()}，一个用户创建的定时任务就能触发
 * <strong>别人的</strong>账户被强制平仓 —— 那是最不能出现在用户任务里的一类副作用。
 * 所以风险检测的定位是"发现并留痕"，处置仍然走既有风控引擎。</p>
 *
 * <p>维持担保比例与风险等级<strong>不自己算</strong>，直接取
 * {@link SimTradeService#getAccountOverview(Long)}（公开只读）的
 * {@code maintMarginPct} / {@code riskStatus}，阈值常量也引用 {@link SimRiskService}。
 * 自己再算一遍就等着和风控引擎给出两个不一样的数字。</p>
 */
@Slf4j
@Component
public class RiskCheckExecutor implements ScheduledTaskExecutor {

    /** 产物里最多保留多少个持仓明细，避免撑爆 artifacts_json（VARCHAR(4000)）。 */
    private static final int MAX_POSITIONS_IN_ARTIFACTS = 10;

    private final SimTradeService simTradeService;
    private final ObjectMapper objectMapper;

    public RiskCheckExecutor(SimTradeService simTradeService, ObjectMapper objectMapper) {
        this.simTradeService = simTradeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ScheduledTaskType type() {
        return ScheduledTaskType.RISK_CHECK;
    }

    @Override
    public TaskExecutionResult execute(ScheduledTask task) {
        RiskCheckParams params = parseParams(task.getParamsJson());
        double warnBelowPct = params.getWarnBelowPct() == null
                ? SimRiskService.WARN_MAINT_PCT
                : params.getWarnBelowPct();
        if (warnBelowPct <= 0 || warnBelowPct > 100) {
            throw new IllegalArgumentException("风险检测参数不合法：warnBelowPct 需在 (0, 100] 之间");
        }

        Map<String, Object> account;
        try {
            account = simTradeService.getAccountOverview(task.getUserId());
        } catch (ResponseStatusException notFound) {
            if (notFound.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw notFound;
            }
            // 没开通模拟盘不算"任务失败"：它和行情扫描拿不到报价是同一类情况 ——
            // 任务正常跑完，只是这次没有可体检的对象。摘要里写清楚，历史里看得见。
            // 若这里也判失败，任务会在 5 次之后被自动暂停，而用户完全不知道为什么。
            return TaskExecutionResult.of("风险检测完成：该账号尚未开通模拟盘，本次无持仓可检测");
        }

        String riskStatus = asText(account.get("riskStatus"));
        Double maintPct = asDouble(account.get("maintMarginPct"));
        String summary = describe(riskStatus, maintPct, warnBelowPct, account);
        String artifacts = writeJson(artifacts(account, warnBelowPct));

        if (isAlert(riskStatus, maintPct, warnBelowPct)) {
            // 执行器只负责产出风险结果；站内提醒由 ScheduledTaskNotificationListener
            // 统一消费 RISK_ALERT 事件，避免在各个执行器里重复接入通知通道。
            log.warn("定时风险检测命中。taskId={} userId={} maintMarginPct={} riskStatus={}",
                    task.getId(), task.getUserId(), maintPct, riskStatus);
        }
        return new TaskExecutionResult(summary, artifacts);
    }

    /** DANGER / WARN 本身就是引擎给的等级；再叠加一个用户自定义阈值，取两者里更严的。 */
    private static boolean isAlert(String riskStatus, Double maintPct, double warnBelowPct) {
        if ("DANGER".equals(riskStatus) || "WARN".equals(riskStatus)) {
            return true;
        }
        return maintPct != null && maintPct < warnBelowPct;
    }

    private static String describe(String riskStatus, Double maintPct, double warnBelowPct,
                                   Map<String, Object> account) {
        int positionCount = positionCount(account);

        if ("NONE".equals(riskStatus) || positionCount == 0) {
            return String.format(Locale.ROOT,
                    "风险检测完成：当前无持仓，无需监控（维持担保比例 %s，账户状态 %s）",
                    pct(maintPct), asText(account.get("status")));
        }

        String head = String.format(Locale.ROOT, "维持担保比例 %s（风险等级 %s，警戒线 %.2f%%）",
                pct(maintPct), riskStatus, warnBelowPct);

        if (isAlert(riskStatus, maintPct, warnBelowPct)) {
            String worst = worstPosition(account);
            return String.format(Locale.ROOT, "风险检测命中：%s｜持仓 %d 个%s",
                    head, positionCount, worst.isEmpty() ? "" : "｜" + worst);
        }
        return String.format(Locale.ROOT, "风险检测通过：%s｜持仓 %d 个", head, positionCount);
    }

    /** 命中时补一句"最差的那个持仓"，让用户不用点开产物就知道该看哪里。 */
    @SuppressWarnings("unchecked")
    private static String worstPosition(Map<String, Object> account) {
        if (!(account.get("positions") instanceof Map<?, ?> positions)) {
            return "";
        }
        String worstSymbol = null;
        Double worstPct = null;
        for (Map.Entry<?, ?> entry : positions.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> detail)) {
                continue;
            }
            Double pct = asDouble(((Map<String, Object>) detail).get("profitPct"));
            if (pct == null) {
                continue;
            }
            if (worstPct == null || pct < worstPct) {
                worstPct = pct;
                worstSymbol = String.valueOf(entry.getKey());
            }
        }
        return worstSymbol == null ? "" : String.format(Locale.ROOT, "浮亏最大 %s %s", worstSymbol, pct(worstPct));
    }

    /**
     * 产物引用：风险快照的标量 + 有上限的持仓明细。
     *
     * <p>{@code positions} 在账户总览里是完整的持仓对象（十几个字段），
     * 持仓一多就会超过 {@code artifacts_json} 的 4000 字符；这里只挑
     * "判断风险要看的那几列"，并截到 {@value #MAX_POSITIONS_IN_ARTIFACTS} 个。</p>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> artifacts(Map<String, Object> account, double warnBelowPct) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("riskStatus", account.get("riskStatus"));
        out.put("maintMarginPct", account.get("maintMarginPct"));
        out.put("warnBelowPct", warnBelowPct);
        out.put("netEquity", account.get("netEquity"));
        out.put("marketValue", account.get("marketValue"));
        out.put("cash", account.get("cash"));
        out.put("totalReturnPct", account.get("totalReturnPct"));
        out.put("accountStatus", account.get("status"));

        List<Map<String, Object>> positions = new ArrayList<>();
        if (account.get("positions") instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (positions.size() >= MAX_POSITIONS_IN_ARTIFACTS) {
                    break;
                }
                if (!(entry.getValue() instanceof Map<?, ?> detail)) {
                    continue;
                }
                Map<String, Object> compact = new LinkedHashMap<>();
                compact.put("symbol", String.valueOf(entry.getKey()));
                compact.put("quantity", detail.get("quantity"));
                compact.put("marketValue", detail.get("marketValue"));
                compact.put("profitPct", detail.get("profitPct"));
                compact.put("stale", detail.get("stale"));
                positions.add(compact);
            }
        }
        out.put("positions", positions);
        out.put("positionsTruncated", positionCount(account) > positions.size());
        return out;
    }

    private static int positionCount(Map<String, Object> account) {
        return account.get("positions") instanceof Map<?, ?> positions ? positions.size() : 0;
    }

    private RiskCheckParams parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return new RiskCheckParams();
        }
        try {
            RiskCheckParams parsed = objectMapper.readValue(paramsJson, RiskCheckParams.class);
            return parsed == null ? new RiskCheckParams() : parsed;
        } catch (Exception invalid) {
            log.warn("风险检测参数解析失败，改用默认值。params={}", paramsJson);
            return new RiskCheckParams();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String pct(Double value) {
        return value == null ? "—" : String.format(Locale.ROOT, "%.2f%%", value);
    }

    private static String asText(Object value) {
        return value == null ? "—" : String.valueOf(value);
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 任务参数。字段可空，缺省用引擎的警戒线。 */
    @Data
    public static class RiskCheckParams {
        private Double warnBelowPct;
    }
}
