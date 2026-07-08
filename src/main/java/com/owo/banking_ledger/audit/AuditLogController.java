package com.owo.banking_ledger.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Audit Logs", description = "Query account audit logs")
@RestController
@RequestMapping("/api/accounts/{accountId}/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    @Operation(summary = "Get paginated account audit logs")
    public AuditLogPageResponse findAuditLogs(
            @PathVariable Long accountId,
            @PageableDefault(
                    size = 20,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC) Pageable pageable) {
        return AuditLogPageResponse.from(
                auditLogService.findAccountAuditLogs(accountId, pageable));
    }
}
