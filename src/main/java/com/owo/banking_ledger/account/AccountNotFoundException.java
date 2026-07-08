package com.owo.banking_ledger.account;

import com.owo.banking_ledger.common.BusinessErrorCode;
import com.owo.banking_ledger.common.BusinessException;

public class AccountNotFoundException extends BusinessException {

    public AccountNotFoundException(Long id) {
        super(BusinessErrorCode.ACCOUNT_NOT_FOUND, "Account not found: " + id);
    }
}
