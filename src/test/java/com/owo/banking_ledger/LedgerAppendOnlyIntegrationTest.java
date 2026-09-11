package com.owo.banking_ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.support.TransactionTemplate;

import com.owo.banking_ledger.account.AccountResponse;
import com.owo.banking_ledger.account.AccountService;
import com.owo.banking_ledger.account.CreateAccountRequest;
import com.owo.banking_ledger.deposit.DepositRequest;
import com.owo.banking_ledger.deposit.DepositResponse;
import com.owo.banking_ledger.deposit.DepositService;
import com.owo.banking_ledger.ledger.LedgerEntry;
import com.owo.banking_ledger.ledger.LedgerEntryRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Posted ledger entries are permanent. These tests go around the application's
 * own posting code and try to change history directly, which the database must
 * refuse.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@WithMockUser(username = "integration-tests", authorities = "SCOPE_ledger:admin")
class LedgerAppendOnlyIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void updatingAPostedEntryIsRejectedByTheDatabase() {
        LedgerEntry entry = postedEntry();

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> executeUpdate(
                        "UPDATE ledger_entries SET amount = 1 WHERE id = "
                                + entry.getId()));

        assertTrue(rootMessage(exception).contains("append-only"),
                () -> "unexpected failure: " + rootMessage(exception));
        assertBigDecimalEquals(entry.getAmount(), reload(entry).getAmount());
    }

    @Test
    void deletingAPostedEntryIsRejectedByTheDatabase() {
        LedgerEntry entry = postedEntry();

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> executeUpdate(
                        "DELETE FROM ledger_entries WHERE id = "
                                + entry.getId()));

        assertTrue(rootMessage(exception).contains("append-only"),
                () -> "unexpected failure: " + rootMessage(exception));
        assertEquals(entry.getId(), reload(entry).getId());
    }

    @Test
    void theEntryRepositoryExposesNoUpdateOrDeleteOperation() {
        List<String> mutators = java.util.Arrays
                .stream(LedgerEntryRepository.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .filter(name -> name.startsWith("delete")
                        || name.startsWith("remove"))
                .toList();

        assertTrue(mutators.isEmpty(),
                () -> "append-only repository exposes " + mutators);
    }

    private LedgerEntry postedEntry() {
        AccountResponse account = accountService.create(
                new CreateAccountRequest("Append Only Customer", "AUD"));

        DepositResponse deposit = depositService.deposit(
                account.id(),
                new DepositRequest(
                        new BigDecimal("100.00"),
                        "AUD",
                        "append-only-" + randomSuffix(),
                        "Append only deposit"));

        return entryRepository
                .findByTransactionId(deposit.transactionId())
                .getFirst();
    }

    private void executeUpdate(String sql) {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery(sql).executeUpdate());
    }

    private LedgerEntry reload(LedgerEntry entry) {
        return entryRepository.findById(entry.getId()).orElseThrow();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        return String.valueOf(root.getMessage());
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
