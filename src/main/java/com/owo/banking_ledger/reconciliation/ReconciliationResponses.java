package com.owo.banking_ledger.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;

/** Read models for the reconciliation query API. */
public final class ReconciliationResponses {

    private ReconciliationResponses() {
    }

    public record RunResponse(
            Long id,
            Instant startedAt,
            Instant finishedAt,
            int accountsChecked,
            int differenceCount,
            String traceId) {

        public static RunResponse from(ReconciliationRun run) {
            return new RunResponse(
                    run.getId(),
                    run.getStartedAt(),
                    run.getFinishedAt(),
                    run.getAccountsChecked(),
                    run.getDifferenceCount(),
                    run.getTraceId());
        }
    }

    public record DifferenceResponse(
            Long id,
            Long runId,
            Long accountId,
            BigDecimal recordedBalance,
            BigDecimal derivedBalance,
            BigDecimal difference,
            Instant detectedAt) {

        public static DifferenceResponse from(ReconciliationDifference difference) {
            return new DifferenceResponse(
                    difference.getId(),
                    difference.getRunId(),
                    difference.getAccountId(),
                    difference.getRecordedBalance(),
                    difference.getDerivedBalance(),
                    difference.getDifference(),
                    difference.getDetectedAt());
        }
    }

    public record RunPageResponse(
            List<RunResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {

        public static RunPageResponse from(Page<ReconciliationRun> page) {
            return new RunPageResponse(
                    page.getContent().stream().map(RunResponse::from).toList(),
                    page.getNumber(),
                    page.getSize(),
                    page.getTotalElements(),
                    page.getTotalPages());
        }
    }

    public record DifferencePageResponse(
            List<DifferenceResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {

        public static DifferencePageResponse from(
                Page<ReconciliationDifference> page) {
            return new DifferencePageResponse(
                    page.getContent().stream()
                            .map(DifferenceResponse::from)
                            .toList(),
                    page.getNumber(),
                    page.getSize(),
                    page.getTotalElements(),
                    page.getTotalPages());
        }
    }
}
