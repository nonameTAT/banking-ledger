package com.owo.banking_ledger.ledger;

import com.owo.banking_ledger.common.BusinessErrorCode;
import com.owo.banking_ledger.common.BusinessException;

public class IdempotencyConflictException extends BusinessException {

    public IdempotencyConflictException(String referenceId) {
        super(
                BusinessErrorCode.IDEMPOTENCY_PAYLOAD_MISMATCH,
                "Transaction reference was already used with a different "
                        + "request payload: " + referenceId);
    }
}
