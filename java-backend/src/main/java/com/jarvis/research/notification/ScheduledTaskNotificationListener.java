package com.jarvis.research.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.schedule.ScheduledTaskAutoPausedEvent;
import com.jarvis.research.schedule.ScheduledTaskRunFinishedEvent;
import com.jarvis.research.schedule.ScheduledTaskType;
import com.jarvis.research.schedule.TaskRunStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Locale;
import java.util.Map;

/**
 * 把定时任务的事实翻译成"要不要提醒用户"。
 *
 * <p>这是本项目第一条通知链路，也是**唯一**决定通知策略的地方 ——
 * 执行内核只广播事实（{@link ScheduledTaskRunFinishedEvent}），本类决定要不要发、发什么等级。
 * 这样"通知策略"以后要改（比如允许用户按任务配置"成功也通知"），只需改这一个类。</p>
 *
 * <p>当前策略（与 PRD「通知机制」对应）：</p>
 *
 * <table>
 *   <tr><th>事实</th><th>通知</th><th>等级</th></tr>
 *   <tr><td>执行 FAILED / TIMEOUT</td><td>{@code TASK_FAILED}</td><td>WARN</td></tr>
 *   <tr><td>连续失败被自动暂停</td><td>{@code TASK_AUTO_PAUSED}</td><td>WARN</td></tr>
 *   <tr><td>风险检测任务执行成功且命中 DANGER</td><td>{@code RISK_ALERT}</td><td><b>RISK</b>（列表置顶）</td></tr>
 *   <tr><td>风险检测任务执行成功且命中 WARN</td><td>{@code RISK_ALERT}</td><td>WARN</td></tr>
 *   <tr><td>其他成功</td><td>不通知</td><td>—</td></tr>
 * </table>
 *
 * <p>⚠️ 两条监听都用 {@link TransactionalEventListener}{@code (fallbackExecution = true)}：
 * 事件是在事务**之外**发布的（见 {@code ScheduledTaskRunner.finish}），
 * 不带 fallback 时监听器根本不会被调用；换成普通 {@link EventListener} 也可以，
 * 但保留事务语义更明确（将来若改到事务内发布，行为不会静默变化）。</p>
 *
 * <p>🔴 <strong>通知写入失败绝不能影响任务执行</strong>：这里整体 try/catch 并只记日志。
 * 通知是"锦上添花"，任务本身跑完并落了执行历史才是主线。</p>
 */
@Component
@Slf4j
public class ScheduledTaskNotificationListener {

    /** 与该任务/执行记录对应的引用类型，前端据此决定点进去看什么。 */
    static final String LINK_SCHEDULED_TASK = "SCHEDULED_TASK";

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public ScheduledTaskNotificationListener(NotificationService notificationService,
                                             ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onRunFinished(ScheduledTaskRunFinishedEvent event) {
        if (event == null || event.userId() == null) {
            return;
        }
        try {
            handleRunFinished(event);
        } catch (RuntimeException failure) {
            log.warn("定时任务通知写入失败，已忽略（不影响任务执行）。taskId={}", event.taskId(), failure);
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onAutoPaused(ScheduledTaskAutoPausedEvent event) {
        if (event == null || event.userId() == null) {
            return;
        }
        try {
            notificationService.raise(event.userId(), NotificationType.TASK_AUTO_PAUSED, NotificationLevel.WARN,
                    "定时任务已自动暂停：" + name(event.taskName()),
                    "连续失败 " + event.consecutiveFailures() + " 次后已停止自动运行，"
                            + "请修正配置或排查上游问题后手动恢复。",
                    LINK_SCHEDULED_TASK, event.taskId());
        } catch (RuntimeException failure) {
            log.warn("自动暂停通知写入失败，已忽略。taskId={}", event.taskId(), failure);
        }
    }

    private void handleRunFinished(ScheduledTaskRunFinishedEvent event) {
        if (event.status() == TaskRunStatus.FAILED || event.status() == TaskRunStatus.TIMEOUT) {
            String label = event.status() == TaskRunStatus.TIMEOUT ? "执行超时" : "执行失败";
            notificationService.raise(event.userId(), NotificationType.TASK_FAILED, NotificationLevel.WARN,
                    "定时任务" + label + "：" + name(event.taskName()),
                    describe(event),
                    LINK_SCHEDULED_TASK, event.taskId());
            return;
        }

        if (event.status() == TaskRunStatus.SUCCESS && event.taskType() == ScheduledTaskType.RISK_CHECK) {
            raiseRiskAlertIfHit(event);
        }
        // 其余成功一律不通知：每天一条"任务成功"只会把真正重要的通知淹掉。
    }

    /**
     * 风险检测命中时提醒；{@code NONE} 或未越过任何阈值时不通知。
     *
     * <p>等级与 {@code RiskCheckExecutor} 的命中口径保持一致：</p>
     * <ul>
     *   <li>引擎 {@code DANGER} → {@link NotificationLevel#RISK}（列表置顶）；</li>
     *   <li>引擎 {@code WARN} → {@link NotificationLevel#WARN}；</li>
     *   <li>引擎仍为 {@code SAFE}，但维持担保比例已经跌破任务自定义的
     *       {@code warnBelowPct} → {@link NotificationLevel#WARN}。</li>
     * </ul>
     * <p>最后一条不能漏：执行器允许用户把警戒线设得比系统默认更严格，
     * 否则会出现“执行历史已经判定风险命中，但站内通知完全不发”的口径分裂。</p>
     */
    private void raiseRiskAlertIfHit(ScheduledTaskRunFinishedEvent event) {
        Map<String, Object> artifacts = readMap(event.artifactsJson());
        if (artifacts == null) {
            return;
        }
        String riskStatus = asText(artifacts.get("riskStatus"));
        Double maintMarginPct = asDouble(artifacts.get("maintMarginPct"));
        Double warnBelowPct = asDouble(artifacts.get("warnBelowPct"));
        NotificationLevel level = switch (riskStatus == null ? "" : riskStatus) {
            case "DANGER" -> NotificationLevel.RISK;
            case "WARN" -> NotificationLevel.WARN;
            case "NONE" -> null;
            default -> maintMarginPct != null && warnBelowPct != null && maintMarginPct < warnBelowPct
                    ? NotificationLevel.WARN
                    : null;
        };
        if (level == null) {
            return;
        }

        notificationService.raise(event.userId(), NotificationType.RISK_ALERT, level,
                "风险检测命中：" + name(event.taskName()) + "（维持担保比例 " + pct(maintMarginPct) + "）",
                event.resultSummary(),
                LINK_SCHEDULED_TASK, event.taskId());
    }

    /** 失败原因优先取异常信息，没有就退回结果摘要 —— 都比一句"失败了"有用。 */
    private static String describe(ScheduledTaskRunFinishedEvent event) {
        String error = event.errorMessage();
        if (error != null && !error.isBlank()) {
            return error;
        }
        String summary = event.resultSummary();
        return summary == null || summary.isBlank() ? "执行未产出结果，详情见执行历史。" : summary;
    }

    private static String name(String taskName) {
        return taskName == null || taskName.isBlank() ? "未命名任务" : taskName;
    }

    private static String pct(Double value) {
        return value == null ? "—" : String.format(Locale.ROOT, "%.2f%%", value);
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

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        } catch (Exception invalid) {
            // 产物 JSON 只可能由我们自己的执行器写入，解析失败说明格式变了，值得留一条日志。
            log.warn("风险通知无法解析执行产物 JSON，跳过本次提醒。", invalid);
            return null;
        }
    }
}
