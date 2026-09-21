package com.jarvis.research.controller;

import com.jarvis.research.audit.AuditEvent;
import com.jarvis.research.audit.AuditReport;
import com.jarvis.research.audit.AuditService;
import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 当前用户只能读取自己的审计事件。 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping("/recent")
    public ApiResponse<List<AuditEvent>> recent(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(auditService.recentForUser(CurrentUser.id(), limit));
    }

    /** 运维报表：当前用户近 N 天审计事件聚合统计。 */
    @GetMapping("/report")
    public ApiResponse<AuditReport> report(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(auditService.reportForUser(CurrentUser.id(), days));
    }
}
