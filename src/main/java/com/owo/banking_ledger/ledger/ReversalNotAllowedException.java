package com.owo.banking_ledger.ledger;

import com.owo.banking_ledger.common.BusinessErrorCode;
import com.owo.banking_ledger.common.BusinessException;

public class ReversalNotAllowedException extends BusinessException {

    public ReversalNotAllowedException(String message) {
        super(BusinessErrorCode.REVERSAL_NOT_ALLOWED, message);
    }
}
