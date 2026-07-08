package com.owo.banking_ledger.deposit;

import com.owo.banking_ledger.common.BusinessErrorCode;
import com.owo.banking_ledger.common.BusinessException;

public class DuplicateTransactionException extends BusinessException {

    public DuplicateTransactionException(String referenceId) {
        super(
                BusinessErrorCode.DUPLICATE_TRANSACTION,
                "Transaction reference already exists: " + referenceId);
    }
}
