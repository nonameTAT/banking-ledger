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
- Idempotency check through unique `referenceId`
- Pessimistic account locking for balance-changing operations
- Global exception handling with structured JSON errors
- Integration and concurrency tests

## Accounting Model

Accounts have an `accountKind` and `accountCategory`.

- Customer accounts are `CUSTOMER` / `LIABILITY`.
- The seeded system cash account is `SYSTEM` / `ASSET`.
- Flyway seeds `SYSTEM-CASH-AUD`.

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
└── V3__create_audit_logs.sql
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
- `DATA_INTEGRITY_VIOLATION`
- `INVALID_REQUEST`

Examples:

- Duplicate `referenceId` returns `409 Conflict`.
- Missing account returns `404 Not Found`.
- Invalid amount or self-transfer returns `400 Bad Request`.

## Validation Rules

- `currency` must be a three-letter uppercase code, for example `AUD`.
- `amount` must be at least `0.0001`.
- `amount` supports up to 15 integer digits and 4 fractional digits.
- `referenceId` is required, unique, and has a maximum length of 64.
- Transfer source and target accounts must be different.
- Deposit, withdrawal, and transfer currencies must match account currency.

## Test Coverage

The test suite includes:

- Service unit tests for account, deposit, withdrawal, and transfer logic
- Web MVC tests for account, deposit, withdrawal, transfer, ledger query, and audit log APIs
- Full banking flow integration test
- Concurrency integration tests for simultaneous withdrawals and transfers

Run all tests:

```bash
./mvnw test
```

Run selected tests:

```bash
./mvnw -Dtest=BankingFlowIntegrationTest test
./mvnw -Dtest=BankingConcurrencyIntegrationTest test
```

## Notes

- `spring.jpa.hibernate.ddl-auto=validate` is enabled, so schema changes must be made through Flyway migrations.
- `spring.jpa.open-in-view=false` is enabled, so query services explicitly fetch required lazy relations.
- Balance-changing operations use pessimistic write locks to protect concurrent updates.

## Progress

# Banking Ledger Project Checklist

## Project Scope

- [x] Define the project as an account-based banking ledger
- [x] Keep customer registration and identity management out of scope
- [x] Store `ownerName` directly on customer accounts
- [x] Document that `Account.balance` is a materialized balance
- [x] Document `LedgerEntry` as the permanent accounting source of truth

## Domain Model

- [x] `Account`
- [x] `LedgerTransaction`
- [x] `LedgerEntry`
- [x] `AuditLog`
- [x] `AccountStatus`
- [x] `AccountKind`
- [x] `AccountCategory`
- [x] `TransactionType`
- [x] `TransactionStatus`
- [x] `EntryType`
- [ ] Add reversal relationship to `LedgerTransaction`
- [ ] Ensure posted ledger entries are append-only
- [x] Define whether failed transactions are persisted or rolled back

## Architecture

- [x] Package-by-feature project structure
- [x] Controller → Service → Repository separation
- [x] Controllers do not access repositories directly
- [x] DTOs are separated from JPA entities
- [x] Service methods define transaction boundaries
- [x] Project class diagram
- [ ] Create a simplified domain-only class diagram
- [ ] Create a transfer sequence diagram
- [ ] Extract common posting logic into `LedgerPostingService`

## Account Module

- [x] `AccountRepository`
- [x] Pessimistic account lookup
- [x] Account creation
- [x] Account lookup
- [x] Account balance query
- [x] Freeze account
- [x] Unfreeze account
- [x] Active-account validation
- [x] Account number uniqueness
- [x] `AccountController`
- [x] `CreateAccountRequest`
- [x] `AccountResponse`
- [x] Account API documentation

## Deposit Module

- [x] `DepositService`
- [x] `DepositController`
- [x] `DepositRequest`
- [x] `DepositResponse`
- [x] Debit the system cash account
- [x] Credit the customer account
- [x] Validate account status
- [x] Validate currency
- [x] Apply pessimistic locking
- [x] Create two ledger entries
- [x] Record deposit audit log
- [x] Document deposit API

## Withdrawal Module

- [x] `WithdrawalService`
- [x] `WithdrawalController`
- [x] `WithdrawalRequest`
- [x] `WithdrawalResponse`
- [x] Debit the customer account
- [x] Credit the system cash account
- [x] Validate sufficient balance
- [x] Validate account status
- [x] Validate currency
- [x] Apply pessimistic locking
- [x] Create two ledger entries
- [x] Record withdrawal audit log
- [x] Document withdrawal API

## Transfer Module

- [x] `TransferService`
- [x] `TransferController`
- [x] `TransferRequest`
- [x] `TransferResponse`
- [x] Validate source and target accounts
- [x] Reject same-account transfers
- [x] Validate sufficient balance
- [x] Validate matching currencies
- [x] Validate account status
- [x] Apply pessimistic locking
- [x] Debit the source account
- [x] Credit the target account
- [x] Create two ledger entries
- [x] Mark the transaction as completed
- [x] Record transfer audit logs
- [x] Roll back the complete operation on failure
- [x] Enforce deterministic account lock ordering
- [x] Document transfer API

## Ledger Module

- [x] `LedgerTransactionRepository`
- [x] `LedgerEntryRepository`
- [x] Find transaction by `referenceId`
- [x] Query ledger entries by account
- [x] Paginate account ledger entries
- [x] `LedgerQueryService`
- [x] `LedgerQueryController`
- [x] `LedgerEntryResponse`
- [x] Store `balanceAfter`
- [x] Create one transaction and two entries per business operation
- [ ] Create a ledger reconciliation query
- [ ] Verify `Account.balance` against ledger-derived balance
- [ ] Make reconciliation account-category aware
- [ ] Prevent updates and deletes of posted ledger entries
- [ ] Add transaction reversal support
- [ ] Link a reversal transaction to its original transaction

## Idempotency

- [x] Require `referenceId`
- [x] Add a unique database constraint for `referenceId`
- [x] Check for existing transactions by `referenceId`
- [x] Return a duplicate-transaction error
- [ ] Define behaviour for repeated identical requests
- [ ] Reject the same `referenceId` with a different request payload
- [ ] Handle concurrent unique-constraint violations consistently
- [ ] Consider storing a request hash for payload comparison

## Audit Module

- [x] `AuditLog`
- [x] `AuditAction`
- [x] `AuditLogRepository`
- [x] `AuditLogService`
- [x] `AuditLogController`
- [x] `AuditLogResponse`
- [x] Paginated audit-log response
- [x] Record account creation events
- [x] Record account freeze and unfreeze events
- [x] Record completed deposit events
- [x] Record completed withdrawal events
- [x] Record completed transfer events
- [x] Query transfer audit logs from either account
- [x] Document audit-log API

## Error Handling

- [x] `BusinessException`
- [x] `BusinessErrorCode`
- [x] `AccountNotFoundException`
- [x] `DuplicateTransactionException`
- [x] `ErrorResponse`
- [x] `GlobalExceptionHandler`
- [x] Handle validation failures
- [x] Handle data-integrity violations
- [x] Handle invalid business requests
- [ ] Add a dedicated reconciliation-failure error code
- [ ] Add a conflicting-idempotency-payload error code
- [ ] Add a reversal-not-allowed error code

## Database

- [x] PostgreSQL setup
- [x] Docker Compose setup
- [x] Spring application configuration
- [x] Flyway migrations
- [x] `accounts` table
- [x] `ledger_transactions` table
- [x] `ledger_entries` table
- [x] `audit_logs` table
- [x] Seed system cash account
- [x] Unique account-number constraint
- [x] Unique `referenceId` constraint
- [x] Foreign-key relationships
- [x] Hibernate schema validation
- [x] Add indexes for account ledger-entry queries
- [x] Add indexes for audit-log queries
- [x] Add database constraints for positive amounts
- [ ] Review currency-column constraints
- [x] Verify decimal precision matches validation rules

## API Documentation

- [x] Swagger / OpenAPI configuration
- [x] Document account APIs
- [x] Document deposit APIs
- [x] Document withdrawal APIs
- [x] Document transfer APIs
- [x] Document ledger-entry APIs
- [x] Document audit-log APIs
- [x] Provide example request bodies
- [x] Provide example response bodies
- [x] Document structured error responses
- [ ] Document idempotency replay behaviour
- [ ] Document reversal API after implementation

## Testing

### Existing Coverage

- [x] Account service tests
- [x] Deposit service tests
- [x] Withdrawal service tests
- [x] Transfer service tests
- [x] Account Web MVC tests
- [x] Deposit Web MVC tests
- [x] Withdrawal Web MVC tests
- [x] Transfer Web MVC tests
- [x] Ledger-query Web MVC tests
- [x] Audit-log Web MVC tests
- [x] Transfer success test
- [x] Insufficient-balance test
- [x] Same-account transfer test
- [ ] Currency-mismatch test
- [x] Idempotency test
- [x] Full banking-flow integration test
- [x] Concurrent withdrawal test
- [x] Concurrent transfer test
- [x] Controller integration tests

### Remaining Tests

- [x] Test deterministic lock ordering
- [ ] Test complete rollback when the second ledger entry fails
- [ ] Test rollback when audit-log creation fails
- [ ] Test concurrent requests using the same `referenceId`
- [ ] Test the same `referenceId` with a different payload
- [ ] Test ledger reconciliation
- [ ] Test account balance against ledger-derived balance
- [ ] Test that posted ledger entries cannot be modified
- [ ] Test reversal transactions
- [ ] Test custom pessimistic-lock repository queries
- [x] Test ledger-entry pagination and ordering
- [ ] Test database amount and precision constraints

## Production Readiness

- [ ] Add structured application logging
- [ ] Add correlation or request IDs
- [ ] Add health and readiness endpoints
- [ ] Add database metrics
- [ ] Add transaction and reconciliation metrics
- [ ] Define retry behaviour for deadlocks
- [ ] Define retention rules for audit logs
- [ ] Add scheduled ledger reconciliation
- [ ] Add authentication and authorization before external deployment

## Next Order

- [ ] 1. Extract `LedgerPostingService`
- [x] 2. Enforce deterministic lock ordering
- [ ] 3. Add reconciliation query and reconciliation tests
- [ ] 4. Define strict idempotency replay behaviour
- [ ] 5. Make ledger entries append-only
- [ ] 6. Add reversal transactions
- [x] 7. Clarify failed-transaction persistence
- [ ] 8. Add database indexes and constraints
- [ ] 9. Add production observability
- [ ] 10. Add authentication and authorization
