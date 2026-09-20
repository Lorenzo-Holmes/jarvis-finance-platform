package com.jarvis.research.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.news.NewsDigest;
import com.jarvis.research.service.AiProxyService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 每日资讯日报执行器：按周期抓一遍资讯、把最新几条写成执行历史里的一行摘要。
 *
 * <p>参数（{@code params_json}）：</p>
 * <pre>
 * {
 *   "limit": 12,        // 可选，摘要与产物里保留的条数；默认 10，上限 20
 *   "headlineCount": 3  // 可选，摘要正文里实际列出的标题条数；默认 3
 * }
 * </pre>
 *
 * <p>⚠️ 这个执行器<strong>只做"抓取 + 留痕"，不推送任何外部渠道</strong>。
 * 用户会不会被提醒属于 PRD「站内通知」那条线的职责（已由 V11 的
 * {@code user_notification} 承接，订阅的是内核广播的
 * {@link ScheduledTaskRunFinishedEvent}），执行器自己再私接一个推送通道，
 * 就会出现"通知中心一条、微信又一条"的双份提醒。所以这里只返回结果对象。</p>
 *
 * <p>与 {@code NewsController} 的关系：<strong>本类不调用那个控制器</strong>。
 * 控制器上的 {@code GET /api/news/daily} 是给浏览器用的外部入口，它带着当前登录用户的
 * 上下文（标题翻译按用户限额）；而定时任务是<strong>后台线程</strong>执行的，
 * 那里没有请求上下文，{@code CurrentUser.id()} 取不到东西。所以这里沿
 * {@code NewsController} 的同一层次直接走 {@link AiProxyService} → Python
 * {@code /internal/rss/digest}，数据源与整形规则（{@link NewsDigest}）与页面完全一致，
 * 只是不重复走一遍 HTTP 自调用。</p>
 *
 * <p>已知接口现象（<strong>本次刻意不改</strong>）：Python 侧 {@code /internal/rss/digest}
 * 声明的是 POST + 查询参数，而 {@code NewsController} 与本类都是
 * {@code post(path, Map.of())} 把参数拼在路径里、body 传空。两者行为一致
 * ——Python 会用默认 {@code refresh=True, force=False}，正是日报想要的语义。
 * 若将来 Python 改成从 body 读参数，Controller 与本类需要一起调整。</p>
 */
@Slf4j
@Component
public class DailyDigestExecutor implements ScheduledTaskExecutor {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;
    private static final int DEFAULT_HEADLINE_COUNT = 3;
    private static final int MAX_HEADLINE_COUNT = 5;
    private static final int MAX_ARTIFACTS_LENGTH = 3999;

    /** 与 {@code scheduled_task} 下 {@code params_json} 的语义一致：留痕里带的参数快照。 */
    private static final String DIGEST_PATH = "/internal/rss/digest?refresh=true&force=false";

    private final AiProxyService aiProxyService;
    private final ObjectMapper objectMapper;

    public DailyDigestExecutor(AiProxyService aiProxyService, ObjectMapper objectMapper) {
        this.aiProxyService = aiProxyService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ScheduledTaskType type() {
        return ScheduledTaskType.DAILY_DIGEST;
    }

    @Override
    public TaskExecutionResult execute(ScheduledTask task) {
        DailyDigestParams params = parseParams(task.getParamsJson());
        int limit = clamp(params.getLimit() == null ? DEFAULT_LIMIT : params.getLimit(),
                DEFAULT_LIMIT, MAX_LIMIT);
        int headlineCount = clamp(
                params.getHeadlineCount() == null ? DEFAULT_HEADLINE_COUNT : params.getHeadlineCount(),
                DEFAULT_HEADLINE_COUNT, MAX_HEADLINE_COUNT);

        Map<String, Object> shaped;
        try {
            Map<String, Object> raw = aiProxyService.post(DIGEST_PATH, Map.of());
            shaped = NewsDigest.fromDigest(raw, limit);
        } catch (Exception unavailable) {
            // 资讯源不可用不算"任务失败"：这是"这次没抓到"而不是"这个任务坏了"。
            // 判失败的话，连续 5 次（比如深夜上游维护）就会把用户的任务自动暂停，
            // 而他完全不知道为什么。降级成 available=false 的摘要，历史里看得见。
            log.warn("每日资讯日报拉取失败，本次降级返回。taskId={}", task.getId(), unavailable);
            shaped = NewsDigest.unavailable(NewsDigest.REASON_UNAVAILABLE);
        }

        if (!Boolean.TRUE.equals(shaped.get("available")) || allSourcesUnavailable(shaped)) {
            String reason = shaped.get("reason") == null
                    ? NewsDigest.REASON_UNAVAILABLE
                    : String.valueOf(shaped.get("reason"));
            return TaskExecutionResult.of(String.format(Locale.ROOT,
                    "每日资讯日报：本次未能取到资讯（原因 %s），已跳过，不影响下次执行",
                    reason));
        }

        List<Map<String, Object>> items = asItems(shaped.get("items"));
        if (items.isEmpty()) {
            // "没有资讯"和"拿不到资讯"是两回事，摘要必须能区分（见 NewsDigest 类注释）。
            return TaskExecutionResult.of("每日资讯日报：本次没有取到任何带标题的资讯（可能所有源都为空）");
        }

        String summary = describe(items, shaped, headlineCount, limit);
        String artifacts = artifactsJson(items, shaped, limit);
        log.info("每日资讯日报完成。taskId={} 条数={} 可用源={}/{}",
                task.getId(), items.size(), shaped.get("ok_sources"), shaped.get("total_sources"));
        return new TaskExecutionResult(summary, artifacts);
    }

    /**
     * 一句话摘要：定时任务的价值就是"不用点开也能看出这次抓到什么"。
     *
     * <p>只列前 {@code headlineCount} 条标题 —— 执行历史的摘要列只有 1000 字符，
     * 把 12 条标题全塞进去会挤掉"来源覆盖情况"这个更重要的健康信号。</p>
     */
    private static String describe(List<Map<String, Object>> items, Map<String, Object> shaped,
                                   int headlineCount, int limit) {
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.ROOT, "每日资讯日报：共 %d 条", items.size()));
        if (items.size() >= limit) {
            text.append(String.format(Locale.ROOT, "（已按上限 %d 条截断）", limit));
        }
        text.append(String.format(Locale.ROOT, "｜资讯源 %s/%s 可用｜",
                shaped.get("ok_sources"), shaped.get("total_sources")));

        List<String> headlines = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (headlines.size() >= headlineCount) {
                break;
            }
            String title = text(item.get("title"));
            String source = text(item.get("source"));
            headlines.add(source.isEmpty() ? title : title + "（" + source + "）");
        }
        text.append(String.join("｜", headlines));
        if (items.size() > headlines.size()) {
            text.append("｜……");
        }
        return text.toString();
    }

    /**
     * 产物引用：条目的标题 / 链接 / 来源 / 发布时间，按上限截断。
     *
     * <p>{@code items} 在 {@link NewsDigest#fromDigest} 里已经按 {@code limit} 截过，
     * 这里再截一次是为了守住 {@code artifacts_json} 的 4000 字符列宽 —— 中文标题较长时
     * 20 条仍有可能接近上限，超长会被内核截断成无法解析的垃圾。所以顺带算一个
     * {@code itemsTruncated}，让读产物的人知道"这里不是全部"。</p>
     */
    private static Map<String, Object> artifacts(List<Map<String, Object>> items,
                                                 Map<String, Object> shaped, int keepCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated_at", shaped.get("generated_at"));
        out.put("ok_sources", shaped.get("ok_sources"));
        out.put("total_sources", shaped.get("total_sources"));
        out.put("limit", keepCount);
        out.put("count", items.size());

        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (kept.size() >= keepCount) {
                break;
            }
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("title", item.get("title"));
            compact.put("source", item.get("source"));
            compact.put("published", item.get("published"));
            compact.put("url", item.get("url"));
            kept.add(compact);
        }
        out.put("items", kept);
        out.put("itemsTruncated", items.size() > kept.size());
        return out;
    }

    /**
     * 产物列是 VARCHAR(4000)，不能依赖内核事后截断，否则会留下非法 JSON。
     * 从当前上限逐条缩减，直到完整 JSON 留在列宽内；即使单条标题异常超长，
     * 也至少留下可解析的元数据和 itemsTruncated=true。
     */
    private String artifactsJson(List<Map<String, Object>> items,
                                 Map<String, Object> shaped, int limit) {
        int keepCount = Math.min(items.size(), limit);
        while (keepCount >= 0) {
            String json = writeJson(artifacts(items, shaped, keepCount));
            if (json != null && json.length() <= MAX_ARTIFACTS_LENGTH) {
                return json;
            }
            keepCount--;
        }
        return null;
    }

    private static boolean allSourcesUnavailable(Map<String, Object> shaped) {
        int totalSources = number(shaped.get("total_sources"));
        int availableSources = number(shaped.get("ok_sources"));
        return totalSources > 0 && availableSources == 0;
    }

    private static int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private DailyDigestParams parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return new DailyDigestParams();
        }
        try {
            DailyDigestParams parsed = objectMapper.readValue(paramsJson, DailyDigestParams.class);
            return parsed == null ? new DailyDigestParams() : parsed;
        } catch (Exception invalid) {
            // 与另外三个执行器同一处理：参数脏不该让任务失败，用默认值跑完并留下告警更有用。
            log.warn("每日资讯日报参数解析失败，改用默认值。params={}", paramsJson);
            return new DailyDigestParams();
        }
    }

    /**
     * 把越界参数夹回合法区间，而不是抛异常。
     *
     * <p>与 {@code BacktestExecutor} 的 {@code limit < longMa} 判失败不同：那里的越界
     * 会让回测结果失去意义（参数自相矛盾），而这里 {@code limit} 只是个展示条数，
     * 用户填 999 想要的显然是"尽量多"，夹到 20 比把任务判失败更符合意图。</p>
     */
    private static int clamp(int value, int fallback, int max) {
        if (value <= 0) {
            return fallback;
        }
        return Math.min(value, max);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asItems(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                items.add((Map<String, Object>) map);
            }
        }
        return items;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 任务参数。字段全部可空，缺省时用默认行为。 */
    @Data
    public static class DailyDigestParams {
        private Integer limit;
        private Integer headlineCount;
    }
}
