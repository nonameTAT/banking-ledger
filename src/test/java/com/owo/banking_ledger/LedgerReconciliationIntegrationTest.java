package com.owo.banking_ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.owo.banking_ledger.account.Account;
import com.owo.banking_ledger.account.AccountCategory;
import com.owo.banking_ledger.account.AccountRepository;
import com.owo.banking_ledger.account.AccountResponse;
import com.owo.banking_ledger.account.AccountService;
import com.owo.banking_ledger.account.CreateAccountRequest;
import com.owo.banking_ledger.account.SystemAccounts;
import com.owo.banking_ledger.audit.AuditLogRepository;
import com.owo.banking_ledger.deposit.DepositRequest;
import com.owo.banking_ledger.deposit.DepositService;
import com.owo.banking_ledger.ledger.EntryType;
import com.owo.banking_ledger.ledger.LedgerEntry;
import com.owo.banking_ledger.ledger.LedgerEntryRepository;
import com.owo.banking_ledger.ledger.LedgerTransaction;
import com.owo.banking_ledger.ledger.LedgerTransactionRepository;
import com.owo.banking_ledger.transfer.TransferRequest;
import com.owo.banking_ledger.transfer.TransferService;
import com.owo.banking_ledger.withdrawal.WithdrawalRequest;
import com.owo.banking_ledger.withdrawal.WithdrawalService;

/**
 * Reconciles materialized account balances against balances derived from
 * ledger entries, applying the asset and liability posting rules.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerReconciliationIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private final List<Long> createdAccountIds = Collections.synchronizedList(new ArrayList<>());
    private final List<String> referenceIds = Collections.synchronizedList(new ArrayList<>());

    // Ledger entries are append-only, so posted test data is never deleted.
    // Each test creates its own accounts and unique reference ids instead.

    @Test
    void customerAccountBalancesMatchLedgerDerivedBalances() {
        Long sourceAccountId = createAccount("Reconciliation Source");
        Long targetAccountId = createAccount("Reconciliation Target");
        String suffix = randomSuffix();

        deposit(sourceAccountId, "reconciliation-deposit-" + suffix, "100.00");
        withdraw(sourceAccountId, "reconciliation-withdrawal-" + suffix, "30.00");
        transfer(sourceAccountId, targetAccountId,
                "reconciliation-transfer-" + suffix, "20.00");

        Account sourceAccount = accountRepository.findById(sourceAccountId).orElseThrow();
        Account targetAccount = accountRepository.findById(targetAccountId).orElseThrow();

        assertEquals(AccountCategory.LIABILITY, sourceAccount.getAccountCategory());
        assertBigDecimalEquals(new BigDecimal("50.00"), sourceAccount.getBalance());
        assertBigDecimalEquals(new BigDecimal("20.00"), targetAccount.getBalance());

        assertReconciled(sourceAccount);
        assertReconciled(targetAccount);
    }

    @Test
    void systemCashAccountBalanceMatchesLedgerDerivedDelta() {
        Long customerAccountId = createAccount("Reconciliation Cash Customer");
        String suffix = randomSuffix();
        Account systemAccount = systemCashAccount();
        BigDecimal balanceBefore = systemAccount.getBalance();

        String depositReferenceId = "reconciliation-cash-deposit-" + suffix;
        String withdrawalReferenceId = "reconciliation-cash-withdrawal-" + suffix;

        deposit(customerAccountId, depositReferenceId, "100.00");
        withdraw(customerAccountId, withdrawalReferenceId, "30.00");

        List<LedgerEntry> systemEntries = entriesFor(
                systemCashAccount().getId(),
                List.of(depositReferenceId, withdrawalReferenceId));

        // The seeded cash account carries balance from earlier runs, so it is
        // reconciled over the entries this test posted rather than absolutely.
        assertEquals(AccountCategory.ASSET, systemAccount.getAccountCategory());
        assertEquals(2, systemEntries.size());
        assertBigDecimalEquals(
                new BigDecimal("70.00"),
                deriveBalance(AccountCategory.ASSET, systemEntries));
        assertBigDecimalEquals(
                systemCashAccount().getBalance().subtract(balanceBefore),
                deriveBalance(AccountCategory.ASSET, systemEntries));

        assertTransactionIsBalanced(depositReferenceId);
        assertTransactionIsBalanced(withdrawalReferenceId);
    }

    private void assertReconciled(Account account) {
        List<LedgerEntry> entries = entriesFor(account.getId());
        BigDecimal running = BigDecimal.ZERO;

        for (LedgerEntry entry : entries) {
            running = running.add(signedAmount(account.getAccountCategory(), entry));
            assertBigDecimalEquals(running, entry.getBalanceAfter());
        }

        assertBigDecimalEquals(
                deriveBalance(account.getAccountCategory(), entries),
                account.getBalance());
        assertBigDecimalEquals(running, account.getBalance());
    }

    private void assertTransactionIsBalanced(String referenceId) {
        LedgerTransaction transaction = transactionRepository
                .findByReferenceId(referenceId)
                .orElseThrow();
        List<LedgerEntry> entries = entryRepository.findByTransactionId(
                transaction.getId());

        assertEquals(2, entries.size());
        assertBigDecimalEquals(
                total(entries, EntryType.DEBIT),
                total(entries, EntryType.CREDIT));
    }

    /**
     * Debits increase assets and reduce liabilities; credits do the opposite.
     */
    private static BigDecimal signedAmount(
            AccountCategory category,
            LedgerEntry entry) {
        boolean increasesBalance = switch (category) {
            case ASSET -> entry.getEntryType() == EntryType.DEBIT;
            case LIABILITY -> entry.getEntryType() == EntryType.CREDIT;
        };

        return increasesBalance
                ? entry.getAmount()
                : entry.getAmount().negate();
    }

    private static BigDecimal deriveBalance(
            AccountCategory category,
            List<LedgerEntry> entries) {
        BigDecimal debits = total(entries, EntryType.DEBIT);
        BigDecimal credits = total(entries, EntryType.CREDIT);

        return switch (category) {
            case ASSET -> debits.subtract(credits);
            case LIABILITY -> credits.subtract(debits);
        };
    }

    private static BigDecimal total(
            List<LedgerEntry> entries,
            EntryType entryType) {
        return entries.stream()
                .filter(entry -> entry.getEntryType() == entryType)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<LedgerEntry> entriesFor(Long accountId) {
        return entryRepository
                .findByAccountId(
                        accountId,
                        PageRequest.of(0, 100, Sort.by(Sort.Direction.ASC, "id")))
                .getContent();
    }

    private List<LedgerEntry> entriesFor(
            Long accountId,
            List<String> transactionReferenceIds) {
        return entryRepository
                .findByAccountId(
                        accountId,
                        PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "id")))
                .getContent()
                .stream()
                .filter(entry -> transactionReferenceIds.contains(
                        entry.getTransaction().getReferenceId()))
                .toList();
    }

    private Account systemCashAccount() {
        return accountRepository
                .findByAccountNumber(SystemAccounts.cashAccountNumber("AUD"))
                .orElseThrow();
    }

    private Long createAccount(String ownerName) {
        AccountResponse response = accountService.create(
                new CreateAccountRequest(ownerName, "AUD"));
        createdAccountIds.add(response.id());
        return response.id();
    }

    private void deposit(
            Long accountId,
            String referenceId,
            String amount) {
        depositService.deposit(
                accountId,
                new DepositRequest(
                        new BigDecimal(amount),
                        "AUD",
                        trackReference(referenceId),
                        "Reconciliation deposit"));
    }

    private void withdraw(
            Long accountId,
            String referenceId,
            String amount) {
        withdrawalService.withdraw(
                accountId,
                new WithdrawalRequest(
                        new BigDecimal(amount),
                        "AUD",
                        trackReference(referenceId),
                        "Reconciliation withdrawal"));
    }

    private void transfer(
            Long sourceAccountId,
            Long targetAccountId,
            String referenceId,
            String amount) {
        transferService.transfer(new TransferRequest(
                sourceAccountId,
                targetAccountId,
                new BigDecimal(amount),
                "AUD",
                trackReference(referenceId),
                "Reconciliation transfer"));
    }

    private String trackReference(String referenceId) {
        referenceIds.add(referenceId);
        return referenceId;
    }

    private static void assertBigDecimalEquals(
            BigDecimal expected,
            BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    private static String randomSuffix() {
        return UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12);
    }
}
