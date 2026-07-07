package com.owo.banking_ledger.account;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

@Getter
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, unique = true)
    private String accountNumber;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_kind", nullable = false)
    private AccountKind accountKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_category", nullable = false)
    private AccountCategory accountCategory;

    protected Account() {
    }

    public Account(
            String accountNumber,
            String ownerName,
            String currency) {
        this.accountNumber = accountNumber;
        this.ownerName = ownerName;
        this.currency = currency;
        this.status = AccountStatus.ACTIVE;
        this.balance = BigDecimal.ZERO;
        this.version = 0L;
        this.createdAt = Instant.now();
        this.accountKind = AccountKind.CUSTOMER;
        this.accountCategory = AccountCategory.LIABILITY;
    }

}
