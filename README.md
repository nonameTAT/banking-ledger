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
- Docker / Docker Compose
- Spring Security (OAuth2 resource server, JWT)
- Spring Boot Actuator, Micrometer, Micrometer Tracing (Brave)
- Prometheus
- k6 (load testing)
- Testcontainers
- GitHub Actions

## Core Features

- Trace id on every request, its log lines, and its error responses
- Metrics and alert rules for transaction errors, database failures, and
  reconciliation differences
- Scheduled ledger reconciliation with queryable results
- Bounded timeouts, with verified behaviour under outages, timeouts, and deadlocks
- Load-tested capacity figures and a rehearsed backup and restore
- Bearer-token authentication on every API request
- Account-level authorization, with a separate administrative permission
- Create and fetch customer accounts
- Freeze and unfreeze customer accounts
- Deposit money into customer accounts
- Withdraw money from customer accounts
- Transfer money between customer accounts
- Query paginated ledger entries for an account
- Query paginated audit logs for an account
- Reverse a posted transaction with a linked correcting transaction
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

Posted entries are append-only. The entity is mapped as immutable, the
repository exposes no update or delete operation, and a database trigger rejects
any `UPDATE` or `DELETE` on `ledger_entries`. A mistake is corrected by posting
a reversal, never by editing history.

## Project Structure

```text
src/main/java/com/owo/banking_ledger
├── account # Account entity, repository, service, controller
├── deposit # Deposit API and business logic
├── withdrawal # Withdrawal API and business logic
├── transfer # Transfer API and business logic
├── reversal # Reversal API and business logic
├── audit # Audit log entity, service, and query API
├── ledger # Ledger transaction/entry entities and query API
└── common # Global exception handling

src/main/resources/db/migration
├── V1__create_accounts.sql
├── V2__create_ledger.sql
├── V3__create_audit_logs.sql
├── V4__add_transaction_request_hash.sql
├── V5__ledger_entries_append_only.sql
└── V6__add_transaction_reversal.sql
```

## UML

The project class diagram is available as a rendered SVG and PlantUML source:

- [Project UML SVG](docs/project-uml.svg)
- [Project UML source](docs/project-uml.puml)

## Prerequisites

- Docker and Docker Compose, for running the stack and for the test databases
- Java 25, only to build or run the application outside a container

## Run Locally

Start the application and its database with a single command:

```bash
docker compose up --build
```

Compose builds the application image from the `Dockerfile` and waits for the
PostgreSQL healthcheck before starting the app, so Flyway never runs against a
database that is still booting. The API runs on:

```text
http://localhost:8080
```

Stop everything with `docker compose down`, or add `-v` to drop the database
volume and start from empty tables.

### Run the application from source

To iterate on the code without rebuilding the image, start only the database and
run the app from Maven:

```bash
docker compose up -d postgres
./mvnw spring-boot:run
```

Database configuration:

```text
url: jdbc:postgresql://localhost:5433/banking_ledger
database: banking_ledger
username: banking
password: banking
```

The application reads standard Spring environment variables, so Compose points it
at the database over the internal network by setting `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`.

## Run Tests

```bash
./mvnw test
```

Tests need a running Docker daemon but no manual database setup. Every
`@SpringBootTest` imports `TestcontainersConfiguration`, which starts a throwaway
`postgres:17` container and wires it in through `@ServiceConnection`. Flyway
migrates that container on startup, so each run begins from a schema built by the
same migrations the application ships, and the local development database on port
`5433` is never read or written.

A new integration test needs `@Import(TestcontainersConfiguration.class)`
alongside `@SpringBootTest` to get its own database.

## Continuous Integration

`.github/workflows/ci.yml` runs on pushes to `main` and on pull requests, in two
jobs:

- `test` runs the full suite on Temurin 25, with Testcontainers supplying
  PostgreSQL, and uploads the Surefire reports.
- `image` runs after the tests pass and builds the container image from the
  `Dockerfile`. It builds only; pushing to a registry would need credentials and
  is deliberately left out.

## Authentication and Authorization

Every API request must carry a bearer token. Only the OpenAPI documents
(`/v3/api-docs`, `/swagger-ui.html`) are reachable without one.

### Who may do what

An account belongs to the identity that opened it, recorded as the token's
`sub` claim in `accounts.owner_subject`. Administrators are callers whose token
carries the `ledger:admin` scope.

| Operation | Permitted caller |
| --- | --- |
| Create an account | Any authenticated caller; it becomes the owner |
| Read an account | Owner or administrator |
| Deposit, withdraw | Owner or administrator |
| Transfer | Owner of the **source** account, or administrator |
| Read ledger entries, audit logs | Owner or administrator |
| Freeze, unfreeze an account | Administrator only |
| Reverse a transaction | Administrator only |

Freezing and reversal are administrative because they act against the account
holder's own interest: freezing removes a customer's access to their money, and
a reversal rewrites the outcome of a transaction across every account it
touched.

A transfer is authorized against the account the money leaves, so holding the
receiving account is not enough to pull funds out of someone else's.

Refused requests answer `401 UNAUTHENTICATED` or `403 ACCESS_DENIED` in the same
JSON error shape as every other failure. A caller asking for an account it does
not own gets `403` rather than `404`, which confirms the id exists; deployments
that treat account ids as secret should map `ACCESS_DENIED` to a not-found
response.

### Identities come from an external provider

Tokens are verified here, never issued. Point the service at an OIDC provider
(Keycloak, Auth0, Cognito, Entra ID) and it fetches and caches that provider's
signing keys:

```bash
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://id.example.com/realms/banking
```

Providers disagree about where permissions live in a token, so the claim, the
authority prefix, and the administrative authority are all configurable:

| Property | Default | Purpose |
| --- | --- | --- |
| `banking.security.authorities-claim` | `scope` | Claim listing the caller's permissions |
| `banking.security.authority-prefix` | `SCOPE_` | Prefix added to each claim value |
| `banking.security.admin-authority` | `SCOPE_ledger:admin` | Authority required for administrative operations |

For Keycloak realm roles, for example, set the claim to `realm_access.roles` and
the admin authority to match the role you grant.

### Running without a provider

So the stack runs end to end on its own, local development verifies tokens it
signs itself, using the HMAC secret in `banking.security.dev-jwt-secret`. The
application logs a warning on startup whenever this is active, and refuses to
start if an issuer URI is configured at the same time.

Mint a token with the bundled script:

```bash
TOKEN=$(scripts/dev-token.sh alice)                  # a customer
ADMIN=$(scripts/dev-token.sh ops-team ledger:admin)  # an administrator

curl -i http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ownerName": "Alice", "currency": "AUD"}'
```

> **Anyone holding that secret can mint a token for any account, including an
> administrative one.** It exists only so the project is runnable without a
> provider. Configure `issuer-uri` for anything else.

## Observability

### Trace ids

Every request runs inside a trace. Its id appears in three places, all the same
value, so a report of "this call failed at 10:42" can be followed straight into
the logs:

- the `X-Trace-Id` response header, on success and on failure alike
- the `traceId` field of any error response body
- the `[traceId-spanId]` field of every log line the request produced

```bash
curl -sD - http://localhost:8080/api/accounts/1 -H "Authorization: Bearer $TOKEN" \
  | grep -i x-trace-id
# x-trace-id: 6aa4567d7677624e63ae5f3446d079a2

docker compose logs app | grep 6aa4567d7677624e63ae5f3446d079a2
```

Sampling is set to 1.0 rather than the default 0.1: the id is used to find one
specific call's logs, which only works if every call has one.

The trace-id filter is ordered deliberately, after the filter that starts the
trace and before Spring Security. Running it later would leave exactly the
rejected requests that most need an id without one.

### Metrics

Exposed at `/actuator/prometheus`:

| Metric | Type | Meaning |
| --- | --- | --- |
| `banking_transaction_errors_total{reason}` | counter | Money movement that did not complete, tagged by error code |
| `banking_database_failures_total{reason}` | counter | Requests that failed on a datastore error rather than on the request itself |
| `banking_reconciliation_runs_total{outcome}` | counter | Completed reconciliation runs, `clean` or `differences` |
| `banking_reconciliation_failures_total{cause}` | counter | Reconciliation runs that threw instead of completing |
| `banking_reconciliation_differences` | gauge | Accounts the last run found disagreeing with their ledger |
| `banking_reconciliation_last_success_timestamp` | gauge | When reconciliation last completed, epoch seconds; `0` means never |

Authentication and authorization refusals are deliberately **not** counted as
transaction errors. A customer reaching for an account they do not own is not
the ledger failing, and alerting on it would bury real faults. A datastore
outage is counted only as a database failure, so one incident does not read as
two unrelated problems.

**Every series is published at zero from startup rather than appearing the
first time it is needed.** A counter that springs into existence already at one
hides the event it was meant to report: `increase()` and `rate()` need two
samples inside their window, so a series born at one and left alone cannot be
told from one that was always one, and the first database failure would produce
no alert. This is also why the tag values are drawn from fixed sets: letting the
datastore decide how many series exist is how a metrics backend gets
overwhelmed, so `reason` on a database failure is a category
(`connection`, `timeout`, `lock`, `other`) and the exact exception type goes to
the log, where the trace id already leads.

Spring reports datastore trouble through two unrelated hierarchies, and both
are counted. `DataAccessException` covers a statement that failed;
`TransactionException` covers never getting as far as running one, which is
what an unreachable database produces. Watching only the first would stay silent
through an outage. Reconciliation runs on a timer and never reaches the
exception handler, so the scheduler records its own failures too.

`/actuator/health` and `/actuator/prometheus` are reachable without a token,
because a load balancer and Prometheus generally cannot hold one. They expose
operational counts rather than account data, and a deployment should still keep
them on an internal network. Every other actuator endpoint needs the
`ledger:admin` scope.

### Reconciliation

`Account.balance` is a materialized figure kept for fast, lock-safe reads;
ledger entries are the source of truth. The two can only drift through a
defect, so a scheduled job recomputes every account's balance from its entries
and records what it finds.

Results are stored, not just counted, because a metric can say that something
drifted but not which account, by how much, or when it started:

```bash
curl -s http://localhost:8080/api/reconciliation/runs        -H "Authorization: Bearer $ADMIN"
curl -s http://localhost:8080/api/reconciliation/differences -H "Authorization: Bearer $ADMIN"
curl -s "http://localhost:8080/api/reconciliation/differences?accountId=2" -H "Authorization: Bearer $ADMIN"
curl -s -X POST http://localhost:8080/api/reconciliation/runs -H "Authorization: Bearer $ADMIN"  # run it now
```

A clean run is recorded too, so that "no differences" can be told apart from
"reconciliation stopped running". `/actuator/health` reports `DOWN` while the
last run found differences.

| Property | Default | Purpose |
| --- | --- | --- |
| `banking.reconciliation.scheduled` | `true` | Whether the timer runs at all |
| `banking.reconciliation.initial-delay` | `PT1M` | Wait before the first run |
| `banking.reconciliation.interval` | `PT5M` | Gap between runs |

### Alerting

`ops/prometheus/alerts.yml` defines the rules. Bring the monitoring stack up
alongside the application:

```bash
docker compose --profile observability up --build
```

Prometheus is then on `http://localhost:9090`, scraping the application and
evaluating:

| Alert | Fires when | Severity |
| --- | --- | --- |
| `BankingTransactionErrors` | Transaction errors average > 0.2/s for 10 minutes | warning |
| `BankingDatabaseFailures` | Any datastore failure in 5 minutes | critical |
| `BankingReconciliationDifferences` | The last run found any disagreeing account | critical |
| `BankingReconciliationStalled` | Nothing reconciled for 30 minutes, **or never** | warning |
| `BankingReconciliationFailing` | Runs are being attempted and throwing | warning |
| `BankingLedgerDown` | The metrics endpoint cannot be scraped | critical |

The thresholds differ on purpose. Some rejections are the system working
correctly, so transaction errors alert on a sustained rate rather than a single
occurrence. A database failure or a balance disagreeing with its entries is
never routine, so one is enough.

`BankingReconciliationStalled` is written against the last-success timestamp
rather than counting runs in a window, because counting cannot report a service
whose reconciliation has failed every time since it started: the run counter
would never move off zero, and a rule reading `== 0` on that cannot tell
"stopped" from "never started". The gauge holds `0` until the first success, so
`time() - 0` is enormous and the alert fires, which is the correct reading of a
ledger that has never once been checked.

## Failure Handling and Capacity

Measured rather than assumed. Two reports carry the numbers:

- [Capacity report](docs/capacity-report.md) — what the service sustains, and
  the defect the load test uncovered
- [Recovery rehearsal](docs/recovery-rehearsal.md) — a real restore, and what it
  cost

### Timeouts

Nothing is allowed to wait indefinitely. Without a bound, a database that has
stopped answering does not fail requests, it holds their threads, and the
service stops serving the endpoints that never needed a database.

Three bounds nest, innermost first, so a request fails at the layer that knows
most about what went wrong rather than at the blunt outer one:

| Bound | Default | What it limits |
| --- | --- | --- |
| `spring.datasource.hikari.connection-timeout` | 3s | Waiting for a pooled connection |
| `spring.transaction.default-timeout` | 10s | How long a transaction may run |
| `statement_timeout` (via `BANKING_STATEMENT_TIMEOUT_MS`) | 30s | A runaway query, in the database |

Reconciliation scans every account, so it is given its own longer transaction
timeout (`banking.reconciliation.transaction-timeout`). It is still capped by
`statement_timeout`, which is the number to raise for a large ledger.

### What failure looks like

| Failure | Behaviour | Verified by |
| --- | --- | --- |
| Database unreachable | `503 DATABASE_UNAVAILABLE`, counted as `connection`, bounded by the connection timeout | `DatabaseOutageTest` |
| Connections killed underneath the pool | The next request succeeds; the pool replaces them | `FailureHandlingIntegrationTest` |
| Query overruns its timeout | Cut off, counted as `timeout` | `FailureHandlingIntegrationTest` |
| Deadlock | PostgreSQL kills one side; the victim is counted as datastore trouble | `FailureHandlingIntegrationTest` |
| Opposing transfers | Do not deadlock, because transfers always lock the lower account id first | `FailureHandlingIntegrationTest` |
| A deposit or transfer that fails mid-request | `503 DATABASE_UNAVAILABLE` with a trace id; balances, entries and the audit row all roll back; the caller's retry posts exactly once | `RequestFailureIntegrationTest` |

The last row is the one that matters to a caller, and it is a separate claim from
the rows above it. Those drive the datastore directly and establish that
PostgreSQL reports trouble and that the trouble arrives classified.
`RequestFailureIntegrationTest` posts to the real endpoints with a failure
injected into the table the service is about to write, and checks the response,
the rollback and the retry together — because a service that classifies a
deadlock perfectly and still leaves half a posting behind has not handled it.

A known gap: when a transaction fails **and** its rollback fails too, Spring
replaces the original exception with the rollback's and logs "Application
exception overridden by rollback exception". What reaches the handler then says
only that a connection could not be rolled back, so a deadlock lands in the
`other` bucket rather than `lock`. The exception class is still logged with the
request's trace id, so it stays diagnosable; nothing can recover the category
from an exception that no longer contains it.

### Capacity

Throughput is flat at **roughly 700–800 requests per second from 5 concurrent
clients to 100**, with no failures at any level. Latency, meanwhile, rises in
proportion to the clients added — a median of 6 ms at 5 becomes 109 ms at 100.
Work in equals work out and everything extra is spent waiting, so the write path
is already saturated at 5 concurrent writers and sizing above that buys latency,
not throughput.

The limit is the write path. Every deposit and withdrawal posts against the
single `SYSTEM-CASH-AUD` account and takes a row lock on it, which serialises
cash movement service-wide. The same shape without writes sustains around 8x the
throughput.

Two things the report is careful about, because both were got wrong first time:
only the steady-state hold window is measured, not the whole run including the
ramp; and throughput varies by up to 15% between identical runs on this host, so
it is a band rather than a figure. See the
[capacity report](docs/capacity-report.md) for the full tables, the method, and
the two defects this work uncovered — an optimistic-locking failure under
contention, and a paginated read whose cost grows without bound as an account
accumulates history.

### Backup and restore

```bash
scripts/backup.sh                      # prints the path it wrote
scripts/restore.sh backups/<file>.dump # replaces the live database
```

Neither script destroys anything it has not first checked. `backup.sh` writes to
a `.partial` file, reads the archive back to confirm it holds the ledger tables,
and only then renames it — so a file named like a backup is one that was read
back successfully. `restore.sh` checks the dump, restores it into a staging
database, and verifies that every balance there matches the entries behind it,
all while the application keeps serving; only then does it stop the application
and swap the two databases by rename. The database being replaced is renamed
aside rather than dropped, so a restore of the wrong dump is still reversible.

A rehearsed restore took 12 seconds, of which the database was unavailable for
about one, and lost every transaction posted after the backup was taken. The
restored ledger reconciles cleanly, which is exactly why the loss is dangerous:
nothing detects it afterwards. See the
[recovery rehearsal](docs/recovery-rehearsal.md) for the limits this
demonstrates, and for what happens to a dump that is no good.

## API

### OpenAPI / Swagger

Every example below needs an `Authorization: Bearer <token>` header; it is left
out of each snippet for brevity. See
[Authentication and Authorization](#authentication-and-authorization) for how to
get one.

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

### Reverse a Transaction

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

## Test Coverage

The test suite includes:

- Service unit tests for account, deposit, withdrawal, and transfer logic
- Web MVC tests for account, deposit, withdrawal, transfer, ledger query, and audit log APIs
- Full banking flow integration test
- Concurrency integration tests for simultaneous withdrawals and transfers
- Ledger reconciliation tests that derive balances from entries
- Idempotency tests for replay, payload mismatch, and concurrent duplicates
- Append-only tests that attempt direct updates and deletes of posted entries
- Reversal tests for mirrored postings, double reversal, and concurrency
- Rollback tests for failed ledger-entry and audit-log writes
- Authorization tests for ownership boundaries, the administrative permission,
  and end-to-end bearer-token verification
- Observability tests for trace id propagation, failure counters, and the
  reachability of the operational endpoints
- Metric tests asserting every failure series is published at zero before
  anything fails, so the first occurrence is an observable change
- Datastore failure tests covering both Spring exception hierarchies, including
  the unreachable-database path that fails before a statement runs
- Reconciliation tests that introduce real balance drift and assert it is
  detected, recorded, and reportable
- Failure handling tests for statement timeouts, killed connections, real
  deadlocks, and contention on the account owner's own path
- Outage tests that stop a database and assert failures are bounded and
  recognisable
- Request failure tests that inject a database fault into a real deposit or
  transfer and assert the response, the full rollback, and that the caller's
  retry posts exactly once

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
./mvnw -Dtest=LedgerAppendOnlyIntegrationTest test
./mvnw -Dtest=ReversalIntegrationTest test
./mvnw -Dtest=FailureHandlingIntegrationTest test
./mvnw -Dtest=RequestFailureIntegrationTest test
./mvnw -Dtest=DatabaseOutageTest test
```

## Notes

- `spring.jpa.hibernate.ddl-auto=validate` is enabled, so schema changes must be made through Flyway migrations.
- `spring.jpa.open-in-view=false` is enabled, so query services explicitly fetch required lazy relations.
- Balance-changing operations use pessimistic write locks to protect concurrent updates.
- Reconciliation records its outcome only after the run's transaction has
  committed. Metrics live in memory and do not roll back, so reporting success
  from inside the transaction would let a failed commit move the last-success
  timestamp, which is exactly what holds off the alert for reconciliation having
  stopped. The comparison therefore runs in its own bean, and the commit happens
  as that call returns.
- Reconciliation reports differences and never repairs them. Ledger entries are
  the source of truth, and silently rewriting a balance would destroy the
  evidence of the defect that caused the drift.
- Authorization is enforced in the services rather than the controllers, so a
  rule cannot be bypassed by reaching an account through a different endpoint.
- Ledger entries are never deleted, so integration tests do not clean up posted
  data. Each test creates its own accounts and unique reference ids, and each
  test run starts from a fresh Testcontainers database.
