package com.owo.banking_ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.owo.banking_ledger.account.Account;
import com.owo.banking_ledger.account.AccountRepository;
import com.owo.banking_ledger.audit.AuditAction;
import com.owo.banking_ledger.audit.AuditLogRepository;
import com.owo.banking_ledger.ledger.EntryType;
import com.owo.banking_ledger.ledger.LedgerEntry;
import com.owo.banking_ledger.ledger.LedgerEntryRepository;
import com.owo.banking_ledger.ledger.LedgerTransaction;
import com.owo.banking_ledger.ledger.LedgerTransactionRepository;
import com.owo.banking_ledger.ledger.TransactionStatus;
import com.owo.banking_ledger.ledger.TransactionType;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BankingFlowIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private AccountRepository accountRepository;

        @Autowired
        private LedgerTransactionRepository transactionRepository;

        @Autowired
        private LedgerEntryRepository entryRepository;

        @Autowired
        private AuditLogRepository auditLogRepository;

        @Test
        void depositWithdrawalTransferAndLedgerQueryUpdateBalancesAndLedger() throws Exception {
                Long sourceAccountId = createAccount("Alice");
                Long targetAccountId = createAccount("Bob");
                String suffix = UUID.randomUUID().toString();
                String depositReferenceId = "it-deposit-" + suffix;
                String withdrawalReferenceId = "it-withdrawal-" + suffix;
                String transferReferenceId = "it-transfer-" + suffix;

                mockMvc.perform(post("/api/accounts/{accountId}/deposits", sourceAccountId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                  "amount": "100.00",
                                                  "currency": "AUD",
                                                  "referenceId": "%s",
                                                  "description": "Integration deposit"
                                                }
                                                """.formatted(depositReferenceId)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.accountId").value(sourceAccountId))
                                .andExpect(jsonPath("$.referenceId").value(depositReferenceId))
                                .andExpect(jsonPath("$.status").value("COMPLETED"))
                                .andExpect(jsonPath("$.balanceAfter").value(100.00));

                mockMvc.perform(post("/api/accounts/{accountId}/withdrawals", sourceAccountId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                  "amount": "30.00",
                                                  "currency": "AUD",
                                                  "referenceId": "%s",
                                                  "description": "Integration withdrawal"
                                                }
                                                """.formatted(withdrawalReferenceId)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.accountId").value(sourceAccountId))
                                .andExpect(jsonPath("$.referenceId").value(withdrawalReferenceId))
                                .andExpect(jsonPath("$.status").value("COMPLETED"))
                                .andExpect(jsonPath("$.balanceAfter").value(70.00));

                mockMvc.perform(post("/api/transfers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                  "sourceAccountId": %d,
                                                  "targetAccountId": %d,
                                                  "amount": "20.00",
                                                  "currency": "AUD",
                                                  "referenceId": "%s",
                                                  "description": "Integration transfer"
                                                }
                                                """.formatted(
                                                sourceAccountId,
                                                targetAccountId,
                                                transferReferenceId)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.sourceAccountId").value(sourceAccountId))
                                .andExpect(jsonPath("$.targetAccountId").value(targetAccountId))
                                .andExpect(jsonPath("$.referenceId").value(transferReferenceId))
                                .andExpect(jsonPath("$.status").value("COMPLETED"))
                                .andExpect(jsonPath("$.sourceBalanceAfter").value(50.00))
                                .andExpect(jsonPath("$.targetBalanceAfter").value(20.00));

                mockMvc.perform(get("/api/accounts/{accountId}/entries", sourceAccountId)
                                .param("page", "0")
                                .param("size", "10"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content.length()").value(3))
                                .andExpect(jsonPath("$.totalElements").value(3))
                                .andExpect(jsonPath("$.content[*].referenceId").value(hasItems(
                                                depositReferenceId,
                                                withdrawalReferenceId,
                                                transferReferenceId)))
                                .andExpect(jsonPath("$.content[*].transactionType").value(hasItems(
                                                "DEPOSIT",
                                                "WITHDRAWAL",
                                                "TRANSFER")));

                Account sourceAccount = accountRepository.findById(sourceAccountId).orElseThrow();
                Account targetAccount = accountRepository.findById(targetAccountId).orElseThrow();

                assertBigDecimalEquals(new BigDecimal("50.00"), sourceAccount.getBalance());
                assertBigDecimalEquals(new BigDecimal("20.00"), targetAccount.getBalance());
                assertTransaction(depositReferenceId, TransactionType.DEPOSIT,
                                EntryType.DEBIT, EntryType.CREDIT);
                assertTransaction(withdrawalReferenceId, TransactionType.WITHDRAWAL,
                                EntryType.DEBIT, EntryType.CREDIT);
                assertTransaction(transferReferenceId, TransactionType.TRANSFER,
                                EntryType.DEBIT, EntryType.CREDIT);
                assertAuditLogActions(sourceAccountId, List.of(
                                AuditAction.ACCOUNT_CREATED,
                                AuditAction.DEPOSIT_COMPLETED,
                                AuditAction.WITHDRAWAL_COMPLETED,
                                AuditAction.TRANSFER_COMPLETED));
                assertAuditLogActions(targetAccountId, List.of(
                                AuditAction.ACCOUNT_CREATED,
                                AuditAction.TRANSFER_COMPLETED));
        }

        private Long createAccount(String ownerName) throws Exception {
                MvcResult result = mockMvc.perform(post("/api/accounts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                  "ownerName": "%s",
                                                  "currency": "AUD"
                                                }
                                                """.formatted(ownerName)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.ownerName").value(ownerName))
                                .andExpect(jsonPath("$.currency").value("AUD"))
                                .andExpect(jsonPath("$.balance").value(0))
                                .andReturn();

                Number id = JsonPath.read(
                                result.getResponse().getContentAsString(),
                                "$.id");
                return id.longValue();
        }

        private void assertTransaction(
                        String referenceId,
                        TransactionType transactionType,
                        EntryType firstEntryType,
                        EntryType secondEntryType) {
                LedgerTransaction transaction = transactionRepository
                                .findByReferenceId(referenceId)
                                .orElseThrow();
                List<LedgerEntry> entries = entryRepository.findByTransactionId(
                                transaction.getId());

                assertEquals(transactionType, transaction.getTransactionType());
                assertEquals(TransactionStatus.COMPLETED, transaction.getStatus());
                assertEquals(2, entries.size());
                assertTrue(entries.stream().anyMatch(entry -> entry.getEntryType() == firstEntryType));
                assertTrue(entries.stream().anyMatch(entry -> entry.getEntryType() == secondEntryType));
        }

        private void assertAuditLogActions(
                        Long accountId,
                        List<AuditAction> expectedActions) {
                List<AuditAction> actions = auditLogRepository
                                .findByAccountIdOrRelatedAccountIdOrderByCreatedAtDesc(
                                                accountId,
                                                accountId,
                                                PageRequest.of(0, 10))
                                .map(auditLog -> auditLog.getAction())
                                .getContent();

                assertTrue(actions.containsAll(expectedActions));
        }

        private static void assertBigDecimalEquals(
                        BigDecimal expected,
                        BigDecimal actual) {
                assertEquals(0, expected.compareTo(actual));
        }
}
