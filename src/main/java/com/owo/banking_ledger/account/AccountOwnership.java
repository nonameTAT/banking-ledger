package com.owo.banking_ledger.account;

/**
 * Just enough of an account to decide who may use it.
 *
 * <p>A projection rather than the entity on purpose. An authorization check
 * that loaded the entity would put it in the persistence context before the
 * balance-changing code takes its pessimistic lock, and Hibernate would then
 * serve that cached copy, and its now-stale {@code @Version}, to the locking
 * read. Under contention the version check at flush fails and a perfectly
 * ordinary request dies with an optimistic locking failure.
 */
public interface AccountOwnership {

    Long getId();

    String getOwnerSubject();
}
