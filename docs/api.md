# API reference

Every endpoint, its request and its response. The service also serves this
from OpenAPI at runtime; see below. Back to the [README](../README.md).

## OpenAPI / Swagger

Every example below needs an `Authorization: Bearer <token>` header; it is left
out of each snippet for brevity. See the README's
[Authentication and Authorization](../README.md#authentication-and-authorization)
for how to get one.

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON:

```text
http://localhost:8080/v3/api-docs
```

## Create Account

```bash
curl -i -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" \
  -d '{
    "ownerName": "Alice",
    "currency": "AUD"
  }'
```

## Get Account

```bash
curl -i http://localhost:8080/api/accounts/2
```

## Freeze Account

```bash
curl -i -X POST http://localhost:8080/api/accounts/2/freeze
```

Frozen accounts reject deposits, withdrawals, and transfers until they are unfrozen.
Freezing a system account returns `400 Bad Request`.

## Unfreeze Account

```bash
curl -i -X POST http://localhost:8080/api/accounts/2/unfreeze
```

## Deposit

```bash
curl -i -X POST http://localhost:8080/api/accounts/2/deposits \
  -H "Content-Type: application/json" \
  -d '{
    "amount": "100.00",
    "currency": "AUD",
    "referenceId": "deposit-001",
    "description": "Initial deposit"
  }'
```

## Withdraw

```bash
curl -i -X POST http://localhost:8080/api/accounts/2/withdrawals \
  -H "Content-Type: application/json" \
  -d '{
    "amount": "30.00",
    "currency": "AUD",
    "referenceId": "withdrawal-001",
    "description": "ATM withdrawal"
  }'
```

## Transfer

```bash
curl -i -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountId": 2,
    "targetAccountId": 4,
    "amount": "20.00",
    "currency": "AUD",
    "referenceId": "transfer-001",
    "description": "Transfer to Bob"
  }'
```

## Query Account Ledger Entries

```bash
curl -i "http://localhost:8080/api/accounts/2/entries?page=0&size=20&sort=createdAt,desc"
```

Response shape:

```json
{
  "content": [
    {
      "id": 1,
      "transactionId": 1,
      "referenceId": "deposit-001",
      "transactionType": "DEPOSIT",
      "entryType": "CREDIT",
      "amount": 100.0,
      "balanceAfter": 100.0,
      "createdAt": "2026-07-08T00:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

## Query Account Audit Logs

```bash
curl -i "http://localhost:8080/api/accounts/2/audit-logs?page=0&size=20&sort=createdAt,desc"
```

Audit logs are written for account creation, account freeze/unfreeze, completed deposits, completed withdrawals, and completed transfers. Transfer audit logs can be queried from either the source or target account.

Response shape:

```json
{
  "content": [
    {
      "id": 1,
      "action": "DEPOSIT_COMPLETED",
      "accountId": 2,
      "relatedAccountId": null,
      "transactionId": 1,
      "referenceId": "deposit-001",
      "amount": 100.0,
      "currency": "AUD",
      "details": "Initial deposit",
      "createdAt": "2026-07-08T00:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

## Reverse a Transaction

```bash
curl -i -X POST http://localhost:8080/api/transactions/1/reversals \
  -H "Content-Type: application/json" \
  -d '{
    "referenceId": "reversal-001",
    "description": "Duplicate deposit"
  }'
```

A reversal posts a new `REVERSAL` transaction whose entries mirror every entry
of the original: each debit becomes a credit and each credit becomes a debit, on
the same accounts and for the same amount. The reversal is linked to what it
corrects through `reversal_of_id`, and the original moves to status `REVERSED`.
Nothing already posted is modified.

Response shape:

```json
{
  "transactionId": 2,
  "referenceId": "reversal-001",
  "originalTransactionId": 1,
  "originalReferenceId": "deposit-001",
  "amount": 100.0,
  "currency": "AUD",
  "status": "COMPLETED"
}
```

Rules:

- Only `COMPLETED` transactions can be reversed.
- A transaction can be reversed at most once, enforced by a partial unique index
  on `reversal_of_id` and by locking the original row while reversing it.
- A reversal cannot itself be reversed.
- Reversals post like any other transaction, so they need active accounts and
  sufficient balance. Reversing a deposit whose money has already been withdrawn
  fails with `INVALID_REQUEST` rather than driving the account negative.
- Reversal requests are idempotent on `referenceId` like every other posting.

Rejected reversals return `409 Conflict` with the code `REVERSAL_NOT_ALLOWED`.

## Error Responses

Errors are returned as structured JSON:

```json
{
  "timestamp": "2026-07-08T00:00:00Z",
  "code": "ACCOUNT_NOT_FOUND",
  "message": "Account not found: 99999",
  "traceId": "6aa4567d7677624e63ae5f3446d079a2"
}
```

Common codes:

- `ACCOUNT_NOT_FOUND`
- `TRANSACTION_NOT_FOUND`
- `DUPLICATE_TRANSACTION`
- `REVERSAL_NOT_ALLOWED`
- `IDEMPOTENCY_PAYLOAD_MISMATCH`
- `DATA_INTEGRITY_VIOLATION`
- `INVALID_REQUEST`
- `UNAUTHENTICATED`
- `ACCESS_DENIED`
- `DATABASE_UNAVAILABLE`

Examples:

- Reusing a `referenceId` with a different payload returns `409 Conflict`.
- Missing account returns `404 Not Found`.
- Invalid amount or self-transfer returns `400 Bad Request`.
- Missing, expired, or untrusted token returns `401 Unauthorized`.
- Reaching an account the caller does not own, or an administrative operation
  without the `ledger:admin` scope, returns `403 Forbidden`.
- A datastore failure returns `503 Service Unavailable` and is counted
  separately from errors the request itself caused.

`traceId` is the id of the trace that produced the error; quote it to find the
request's log lines.

## Validation Rules

- `currency` must be a three-letter uppercase code, for example `AUD`.
- Account creation requires a system cash account for the currency.
- Freeze and unfreeze are limited to customer accounts.
- `amount` must be at least `0.0001`.
- `amount` supports up to 15 integer digits and 4 fractional digits.
- `referenceId` is required, unique, and has a maximum length of 64.
- Repeating a `referenceId` with an identical payload replays the result.
- Transfer source and target accounts must be different.
- Deposit, withdrawal, and transfer currencies must match account currency.
