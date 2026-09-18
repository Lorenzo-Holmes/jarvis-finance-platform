package com.jarvis.research.notification;

/**
 * 通知的紧急程度：**排序依据**（PRD 要求「高风险事件优先提醒」）。
 *
 * <p>⚠️ 不要用 {@code level} 的名字直接排序：数据库里存的是枚举名（`@Enumerated(STRING)`），
 * 按字母序是 `INFO &lt; RISK &lt; WARN` —— 恰好把最紧急的 RISK 排到了中间。
 * 所以列表查询用 {@link #rank()} 对应的 CASE 表达式排序，并由
 * {@code UserNotificationRepositoryTest} 钉住"RISK 确实排在最前"。</p>
 */
public enum NotificationLevel {

    /** 最高优先：需要用户尽快处理（如风险检测命中、账户接近强平线）。 */
    RISK(0),

    /** 需要知道但不紧急：任务失败、任务被自动暂停。 */
    WARN(1),

    /**
     * 信息类：本期**不产生**（成功不通知），保留是为了等级模型完整 ——
     * 将来若开放"按任务配置成功通知"，不必再改表与前端。
     */
    INFO(2);

    private final int rank;

    NotificationLevel(int rank) {
        this.rank = rank;
    }

    /** 排序权重，**越小越靠前**。JPQL 里的 CASE 表达式与此保持一致。 */
    public int rank() {
        return rank;
    }
}
