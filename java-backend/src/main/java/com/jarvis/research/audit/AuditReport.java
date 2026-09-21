package com.jarvis.research.audit;

import java.util.List;

/** 运维报表：当前用户近 N 天审计事件聚合。 */
public record AuditReport(
        int days,
        long total,
        long today,
        List<DailyCount> daily,
        List<ActionCount> actions
) {
    public record DailyCount(String date, long count) {}

    public record ActionCount(String action, long count) {}
}
