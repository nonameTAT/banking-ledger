package com.owo.banking_ledger.ledger;

import com.owo.banking_ledger.common.BusinessErrorCode;
import com.owo.banking_ledger.common.BusinessException;

public class TransactionNotFoundException extends BusinessException {

    public TransactionNotFoundException(Long transactionId) {
        super(
                BusinessErrorCode.TRANSACTION_NOT_FOUND,
                "Transaction not found: " + transactionId);
    }
}
