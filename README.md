# Banking Ledger

A Spring Boot banking ledger API backed by PostgreSQL and Flyway. The project models customer accounts, cash deposits, withdrawals, transfers, and account ledger entries using double-entry accounting.

## Tech Stack

- Java 25
- Spring Boot 4.1
- Spring Web MVC
- Spring Data JPA / Hibernate
- PostgreSQL 17
- Flyway
- Maven Wrapper
- Docker Compose

## Core Features

- Create and fetch customer accounts
- Freeze and unfreeze customer accounts
- Deposit money into customer accounts
- Withdraw money from customer accounts
- Transfer money between customer accounts
- Query paginated ledger entries for an account
- Query paginated audit logs for an account
- Idempotent replay through unique `referenceId`
- Pessimistic account locking for balance-changing operations
- Global exception handling with structured JSON errors
- Integration and concurrency tests

## Accounting Model

Accounts have an `accountKind` and `accountCategory`.

- Customer accounts are `CUSTOMER` / `LIABILITY`.
- The seeded system cash account is `SYSTEM` / `ASSET`.
- Flyway seeds `SYSTEM-CASH-AUD`.

A currency is supported only while a `SYSTEM-CASH-<CURRENCY>` account exists,
because deposits and withdrawals post against it. Account creation is restricted
to those currencies, so `AUD` is the only currency accepted until another system
cash account is seeded. System accounts are internal, so the customer account
API refuses to freeze or unfreeze them.

Posting rules:

- Deposit:
  - Debit system cash account
  - Credit customer account
- Withdrawal:
  - Debit customer account
  - Credit system cash account
- Transfer:
  - Debit source customer account
  - Credit target customer account

Each business transaction creates:

- one `ledger_transactions` row
- two `ledger_entries` rows

`ledger_entries` are the permanent accounting source of truth. `Account.balance`
is a materialized balance maintained for fast reads and concurrency-safe writes.
Business failures roll back the whole transaction; the current application does
not persist failed ledger transactions in normal validation-failure paths.

## Project Structure

```text
src/main/java/com/owo/banking_ledger
├── account # Account entity, repository, service, controller
├── deposit # Deposit API and business logic
├── withdrawal # Withdrawal API and business logic
├── transfer # Transfer API and business logic
├── audit # Audit log entity, service, and query API
├── ledger # Ledger transaction/entry entities and query API
└── common # Global exception handling

src/main/resources/db/migration
├── V1__create_accounts.sql
├── V2__create_ledger.sql
├── V3__create_audit_logs.sql
└── V4__add_transaction_request_hash.sql
```

## UML

The project class diagram is available as a rendered SVG and PlantUML source:

- [Project UML SVG](docs/project-uml.svg)
- [Project UML source](docs/project-uml.puml)

## Prerequisites

- Java 25
- Docker
- Docker Compose

## Run Locally

Start PostgreSQL:

```bash
docker compose up -d
```

Run the application:

```bash
./mvnw spring-boot:run
```

The API runs on:

```text
http://localhost:8080
```

Database configuration:

```text
url: jdbc:postgresql://localhost:5433/banking_ledger
database: banking_ledger
username: banking
password: banking
```

## Run Tests

```bash
./mvnw test
```

Some tests start a full Spring context and connect to the local PostgreSQL instance on port `5433`, so keep Docker Compose running.

## API

### OpenAPI / Swagger

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON:

```text
http://localhost:8080/v3/api-docs
```

### Create Account

```bash
curl -i -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" \
  -d '{
    "ownerName": "Alice",
    "currency": "AUD"
  }'
```

### Get Account

```bash
curl -i http://localhost:8080/api/accounts/2
```

### Freeze Account

```bash
curl -i -X POST http://localhost:8080/api/accounts/2/freeze
```

Frozen accounts reject deposits, withdrawals, and transfers until they are unfrozen.
Freezing a system account returns `400 Bad Request`.

### Unfreeze Account

```bash
curl -i -X POST http://localhost:8080/api/accounts/2/unfreeze
```

### Deposit

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

### Withdraw

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

### Transfer

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

### Query Account Ledger Entries

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

### Query Account Audit Logs

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

## Idempotency

Every posting carries a `referenceId` that identifies the request. Alongside it
the ledger stores a SHA-256 fingerprint of the payload that created the
transaction, so a retry can be told apart from a reused reference id.

- Retrying with the same `referenceId` and the same payload returns the original
  result — the same transaction id and the balance recorded at the time of the
  original posting, not the current balance. Nothing is posted twice.
- Reusing a `referenceId` with a different payload returns `409 Conflict` with
  the code `IDEMPOTENCY_PAYLOAD_MISMATCH`.
- Concurrent duplicates queue on a transaction-scoped advisory lock keyed by the
  reference id. The first request posts, the rest replay its result.

The fingerprint covers the transaction type, the account ids, the amount, the
currency, and the description. Amounts are compared by value, so `100.00` and
`100.0000` are the same payload. A transaction written before fingerprinting
existed has no stored hash and cannot be verified, so retrying it returns
`409 Conflict` with the code `DUPLICATE_TRANSACTION`.

## Error Responses

Errors are returned as structured JSON:

```json
{
  "timestamp": "2026-07-08T00:00:00Z",
  "code": "ACCOUNT_NOT_FOUND",
  "message": "Account not found: 99999"
}
```

Common codes:

- `ACCOUNT_NOT_FOUND`
- `DUPLICATE_TRANSACTION`
- `IDEMPOTENCY_PAYLOAD_MISMATCH`
- `DATA_INTEGRITY_VIOLATION`
- `INVALID_REQUEST`

Examples:

- Reusing a `referenceId` with a different payload returns `409 Conflict`.
- Missing account returns `404 Not Found`.
- Invalid amount or self-transfer returns `400 Bad Request`.

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

## Test Coverage

The test suite includes:

- Service unit tests for account, deposit, withdrawal, and transfer logic
- Web MVC tests for account, deposit, withdrawal, transfer, ledger query, and audit log APIs
- Full banking flow integration test
- Concurrency integration tests for simultaneous withdrawals and transfers
- Ledger reconciliation tests that derive balances from entries
- Idempotency tests for replay, payload mismatch, and concurrent duplicates
- Rollback tests for failed ledger-entry and audit-log writes

Run all tests:

```bash
./mvnw test
```

Run selected tests:

```bash
./mvnw -Dtest=BankingFlowIntegrationTest test
./mvnw -Dtest=BankingConcurrencyIntegrationTest test
./mvnw -Dtest=LedgerReconciliationIntegrationTest test
./mvnw -Dtest=LedgerRollbackIntegrationTest test
./mvnw -Dtest=IdempotencyIntegrationTest test
```

## Notes

- `spring.jpa.hibernate.ddl-auto=validate` is enabled, so schema changes must be made through Flyway migrations.
- `spring.jpa.open-in-view=false` is enabled, so query services explicitly fetch required lazy relations.
- Balance-changing operations use pessimistic write locks to protect concurrent updates.
