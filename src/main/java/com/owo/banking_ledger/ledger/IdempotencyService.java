package com.owo.banking_ledger.ledger;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.owo.banking_ledger.common.RequestFingerprint;
import com.owo.banking_ledger.deposit.DuplicateTransactionException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Guards a posting against duplicate submissions of the same reference id.
 *
 * <p>The transaction-scoped advisory lock makes concurrent submissions of one
 * reference id queue behind each other, so the first request posts and every
 * later request finds the committed transaction and replays it instead of
 * racing into the unique constraint.
 */
@Service
public class IdempotencyService {

    private static final String ADVISORY_LOCK =
            "SELECT pg_advisory_xact_lock(:key)";

    private final LedgerTransactionRepository transactionRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public IdempotencyService(
            LedgerTransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Claims the reference id for the calling transaction and returns the
     * original transaction when this request is a replay of it.
     *
     * @return the transaction to replay, or empty when the posting is new
     * @throws IdempotencyConflictException when the reference id was used with
     *                                      a different payload
     * @throws DuplicateTransactionException when the reference id belongs to a
     *                                       transaction whose payload cannot be
     *                                       verified
     */
    public Optional<LedgerTransaction> claim(
            String referenceId,
            String requestHash) {
        lock(referenceId);

        Optional<LedgerTransaction> existing = transactionRepository
                .findByReferenceId(referenceId);

        if (existing.isEmpty()) {
            return Optional.empty();
        }

        LedgerTransaction transaction = existing.get();

        if (transaction.getRequestHash() == null) {
            throw new DuplicateTransactionException(referenceId);
        }

        if (!transaction.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(referenceId);
        }

        return existing;
    }

    private void lock(String referenceId) {
        entityManager.createNativeQuery(ADVISORY_LOCK)
                .setParameter("key", RequestFingerprint.lockKey(referenceId))
                .getSingleResult();
    }
}
