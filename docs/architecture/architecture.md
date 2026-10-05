# banking-ledger architecture

**Revision 3 · implementation baseline.** Checked against `main` @ `84a92e9` on 5 October 2026.

This document describes the target architecture for adding a web frontend to banking-ledger. Parts of it are already in the code; the rest is planned. The diagram marks which is which, and [section 12](#12-planned-changes-and-implementation-order) lists every planned change with the evidence that it is done. Until a change lands, the code on `main` is the authority, not this document.

## Diagram

![banking-ledger target architecture, revision 3: a React SPA reaches the system only through nginx on port 443, which serves app.example.com (SPA and /api/*) and auth.example.com (Keycloak). Inside the internal Docker network sit the Spring Boot app with its five layers, Keycloak with its own database, PostgreSQL holding the ledger, Prometheus and Alertmanager. CI and the backup scripts sit outside the network.](architecture.drawio.png)

The source is [`architecture.drawio`](architecture.drawio). Edit it in diagrams.net or the Draw.io Integration extension for VS Code, then export it over `architecture.drawio.png` so the image in this document stays current.

| Colour | Meaning |
| --- | --- |
| Blue | Existing: describes the code on `main` |
| Green | New: planned component that does not exist yet |
| Yellow | Changed: exists, but needs the change written on it |
| Dashed border | Optional: build only if the condition on the box applies |

## 1. Scope

banking-ledger is a double-entry ledger API: customer accounts, deposits, withdrawals, transfers, reversals, paginated ledger and audit history, and a scheduled reconciliation of stored balances against the entries behind them. It is a Spring Boot 4.1 service on Java 25 backed by PostgreSQL 17.

This revision adds what a browser frontend needs: a real identity provider in place of locally signed development tokens, a single public entry point, an API contract that is safe for JavaScript clients, and the deployment and test changes that go with them. The ledger's accounting model, idempotency, locking and reconciliation do not change.

## 2. Request paths

Three paths cross the system, and each one uses a different route.

**API calls.** The SPA calls `https://app.example.com/api/...`. nginx forwards `/api/*` to `app:8080` inside the internal network, so the SPA and the API share an origin and no CORS is needed. In the app the request passes the tracing filter, the trace-id filter and Spring Security, then reaches a controller, a service, and PostgreSQL over JDBC.

**Login.** The SPA sends the browser to `https://auth.example.com/realms/...` for the Authorization Code flow with PKCE, and calls the same host to exchange the code and later to refresh tokens. nginx forwards these paths to Keycloak. The SPA never talks to Keycloak by its container name.

**Signing keys.** The app fetches Keycloak's public keys (JWKS) from `http://keycloak:8080/...` over the internal network. This does not change the issuer the app expects: tokens still carry the public `iss` of `https://auth.example.com/realms/banking`, and the app checks for exactly that value.

## 3. Deployment and network boundary

Everything except nginx lives on the internal Docker network and publishes no host port in production. The table shows the current `compose.yaml` against the target.

| Service | Role | Host port on `main` | Production target | Development (`compose.override.yaml`) |
| --- | --- | --- | --- | --- |
| nginx | TLS, SPA files, reverse proxy | not present | `443` | `443` or a local port |
| app | banking-ledger API | `8080:8080` | none | `127.0.0.1:8080:8080` |
| keycloak | identity provider | none | none, reached through nginx | none |
| postgres | ledger and keycloak databases | `5433:5432` | none | `127.0.0.1:5433:5432` |
| prometheus | metrics and alert rules | `9090:9090` | none | `127.0.0.1:9090:9090` |
| alertmanager | alert routing (optional) | not present | none | none |

Routing through nginx is not enough on its own to keep a port private. Docker binds published ports on every host interface by default, and on Linux its iptables rules commonly bypass host firewalls such as ufw. A port that must not be reachable must not be published. The base `compose.yaml` therefore declares no `ports` for internal services, and the development override adds them bound to `127.0.0.1`. The override has to carry them because Compose merges `ports` from an override into the base list: an override can add ports but cannot remove one the base already publishes, short of `!reset`.

Prometheus scrapes `app:8080` over the internal network and is unaffected by removing the app's host port. The k6 scripts call `localhost:8080`, which the development binding keeps working.

### nginx

One nginx container serves two server blocks on port 443.

| Server block | Serves | Proxies | Does not expose |
| --- | --- | --- | --- |
| `app.example.com` | SPA static files, with fallback to `index.html` | `/api/*` to `app:8080` | `/actuator/*`, `/v3/api-docs`, `/swagger-ui*` |
| `auth.example.com` | nothing of its own | `/realms/*`, `/resources/*` to `keycloak:8080` | `/admin/*` |

`/actuator/health` and `/actuator/prometheus` need no token by design, so a load balancer and Prometheus can reach them. That is safe only because they are reachable from the internal network alone. Proxying `/` wholesale to the app would publish them.

When nginx itself answers with 502 or 504 because the app is down or slow, the response never passed through the app and carries no `X-Trace-Id`. nginx adds `X-Request-Id` (from `$request_id`) to those responses so a user still has an id to report.

## 4. Identity, authentication and authorization

### 4.1 Keycloak

**Status: existing.** Keycloak is the only token issuer outside development. It runs in `compose.yaml` (image `quay.io/keycloak/keycloak:26.8.0`, production `start` mode, no host port), and its configuration is versioned as [`ops/keycloak/realm-export.json`](../../ops/keycloak/realm-export.json), holding the realm, clients, roles and mappers but no users. Keycloak imports it on first start and skips it once the realm exists. A one-shot `keycloak-db` service creates the `keycloak` database and user on every start if they are missing, so an existing ledger volume gets them too. `scripts/seed-demo-users.sh` creates the demo users for development, CI and E2E only. User accounts, and the `sub` values that `accounts.owner_subject` refers to, live only in Keycloak's database and are protected by the backups in [section 10](#10-operations).

| Setting | Value | Why |
| --- | --- | --- |
| `KC_HOSTNAME` | `https://auth.example.com`, from `BANKING_AUTH_URL` | Fixes the public URL, and with it the `iss` claim the app validates |
| `KC_PROXY_HEADERS` | `xforwarded` | Keycloak sits behind nginx and must trust its forwarded headers to build correct URLs |
| SPA client | public client, PKCE `S256`, exact redirect URIs | A browser cannot keep a client secret |
| SPA client Web Origins | `https://app.example.com` | The token and refresh calls go cross-origin from the app host to the auth host |
| API client | client role `ledger:admin` | Administrators are granted this role, nobody else |
| Mapper | User Client Role, API client, claim `ledger_roles`, multivalued, in the access token | Puts the caller's actual roles in a top-level claim the app can read |
| Mapper | Audience, adds the API client id to `aud` | Keycloak does not include the API in `aud` by default |
| Database | own `keycloak` database and user | Identity data needs persistence and its own backup; sharing the PostgreSQL instance is allowed |

The SPA keeps tokens in memory only, refreshes them through the auth host, and logs out through Keycloak's end-session endpoint.

### 4.2 Token contract

An access token for an administrator carries at least these claims. The realm name and API client id below are illustrative; the shape is the contract.

```json
{
  "iss": "https://auth.example.com/realms/banking",
  "aud": ["banking-ledger-api"],
  "sub": "3f9c2b1e-7a4d-4e0b-9a51-1d2c6b8e4f70",
  "ledger_roles": ["ledger:admin"]
}
```

A customer's token has no `ledger:admin` in `ledger_roles`; the claim may be absent or empty. The mapper derives the claim from the user's real role assignments, never from a hard-coded value. The only acceptable test of this contract is a real Keycloak token, not a hand-built one: a customer calling an administrative endpoint gets 403, and an administrator gets through.

### 4.3 Resource server configuration

**Status: existing.** The app verifies tokens and never issues them. Production and development differ as follows. Compose derives `issuer-uri` and Keycloak's `KC_HOSTNAME` from the same `BANKING_AUTH_URL`, so they cannot drift apart.

| Property | Production | Development profile |
| --- | --- | --- |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `https://auth.example.com/realms/banking` | unset |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | `http://keycloak:8080/realms/banking/protocol/openid-connect/certs` | unset |
| `spring.security.oauth2.resourceserver.jwt.audiences` | the API client id | unset |
| `banking.security.authorities-claim` | `ledger_roles` | `scope` |
| `banking.security.authority-prefix` | `SCOPE_` | `SCOPE_` |
| `banking.security.admin-authority` | `SCOPE_ledger:admin` | `SCOPE_ledger:admin` |
| `banking.security.dev-jwt-secret` | unset, so no HMAC decoder exists | a local value |

Three details in this table are deliberate. Spring Security's `JwtGrantedAuthoritiesConverter` looks a claim up by its literal top-level name and cannot follow a path such as `realm_access.roles`, which is why the roles go into the top-level `ledger_roles` claim; the README's advice to use `realm_access.roles` does not work and should be corrected. `issuer-uri` checks who signed a token, not whom it was issued for, so `audiences` is set as well. And when both `issuer-uri` and `jwk-set-uri` are set, Spring Boot takes keys from the internal JWKS address while still requiring the public issuer.

The prefix and admin authority keep their current defaults, so only `authorities-claim` changes in production. With it, the claim value `ledger:admin` becomes the authority `SCOPE_ledger:admin`, which is what the services and the `/actuator/**` rule already check.

### 4.4 Development tokens

**Status: existing.** `SecurityConfig.devJwtDecoder` exists only under the `dev` profile, which nothing activates by default. The secret and `authorities-claim=scope` live in `application-dev.properties`; `scripts/dev-token.sh` puts permissions in the `scope` claim. Without the profile no HMAC decoder exists, whatever `BANKING_DEV_JWT_SECRET` is set to. The app refuses to start when the secret is set together with `issuer-uri` or `jwk-set-uri`, under any profile, when the profile is on but the secret is blank, and when no token source is configured at all. `TokenSourceConfigurationTest` covers each of these against the shipped configuration files, and `OidcResourceServerIntegrationTest` starts the whole app with only OIDC settings. The tests that rely on development tokens activate the profile explicitly.

Development has two modes, never mixed. The full stack, `docker compose up`, uses Keycloak with the profile off. Backend-only debugging layers `compose.dev-token.yaml` on top, which turns the profile on and removes the identity provider settings. Once #30 adds `compose.override.yaml`, the backend-only command names it too.

Switching from development tokens to Keycloak changes every caller's `sub`. An account opened by the development identity `alice` stays owned by `alice` and is not visible to the Keycloak user Alice, whose `sub` is a UUID. Development data is reset, or migrated with the one-off update documented in the README, when the switch is made.

### 4.5 Authorization rules

These rules exist on `main` and do not change. Rules about accounts and transactions are enforced in the services through `AccountAccessPolicy`, not in the controllers, so no endpoint can route around them. The reconciliation endpoints, which touch no single account, call the same policy from their controller.

| Operation | Permitted caller |
| --- | --- |
| Create an account | Any authenticated caller, who becomes its owner |
| List accounts | A customer sees their own; an administrator sees all and may filter by owner and kind |
| Read an account, its entries, its audit log | Owner or administrator |
| Deposit, withdraw | Owner or administrator |
| Transfer | Owner of the source account, or administrator |
| Freeze, unfreeze | Administrator only |
| Reverse a transaction | Administrator only |
| Reconciliation runs and differences | Administrator only |

A caller asking for an account it does not own gets `403 ACCESS_DENIED` rather than 404, which confirms the id exists. A deployment that treats account ids as secret maps that response to not-found.

## 5. Application structure

The app is one Spring Boot service in five layers. The diagram shows them top to bottom in the order a request meets them.

### 5.1 Request pipeline

The tracing filter (Micrometer Tracing with Brave, sampling 1.0) starts a trace for every request. `TraceIdResponseFilter` runs next, before Spring Security, so every response the app produces carries `X-Trace-Id`, including 401 and 403 refusals. Sampling is 1.0 rather than the default 0.1 because the id is used to find one specific call's logs, which only works if every call has one.

CORS is needed only if a browser calls the API from a different origin, which the nginx design avoids. If it is ever enabled, it goes through Spring Security's `cors()` support so that preflight `OPTIONS` requests are answered before authentication; otherwise they carry no token and are refused with 401. `X-Trace-Id` must then be listed in `Access-Control-Expose-Headers` for the browser to read it.

### 5.2 Web layer

| Method and path | Caller | Notes |
| --- | --- | --- |
| `POST /api/accounts` | any authenticated | caller becomes the owner |
| `GET /api/accounts` | any authenticated | paged, default size 20, ordered by id; administrators may filter by `ownerSubject` and `accountKind` |
| `GET /api/accounts/{id}` | owner or admin | |
| `POST /api/accounts/{id}/freeze`, `/unfreeze` | admin | customer accounts only |
| `POST /api/accounts/{id}/deposits`, `/withdrawals` | owner or admin | idempotent by `referenceId` |
| `POST /api/transfers` | owner of source, or admin | idempotent by `referenceId`; see [5.3](#53-service-layer) for the response change |
| `POST /api/transactions/{id}/reversals` | admin | idempotent by `referenceId` |
| `GET /api/accounts/{id}/entries` | owner or admin | paged |
| `GET /api/accounts/{id}/audit-logs` | owner or admin | paged |
| `GET /api/reconciliation/runs`, `POST /api/reconciliation/runs` | admin | `POST` runs a reconciliation now |
| `GET /api/reconciliation/differences` | admin | optional `accountId` filter |

Every failure, including authentication and authorization refusals, uses one JSON shape:

```json
{
  "timestamp": "2026-10-05T02:14:07.512Z",
  "code": "IDEMPOTENCY_PAYLOAD_MISMATCH",
  "message": "...",
  "traceId": "6aa4567d7677624e63ae5f3446d079a2"
}
```

| Code | Status | Code | Status |
| --- | --- | --- | --- |
| `INVALID_REQUEST` | 400 | `DUPLICATE_TRANSACTION` | 409 |
| `UNAUTHENTICATED` | 401 | `IDEMPOTENCY_PAYLOAD_MISMATCH` | 409 |
| `ACCESS_DENIED` | 403 | `REVERSAL_NOT_ALLOWED` | 409 |
| `ACCOUNT_NOT_FOUND` | 404 | `DATA_INTEGRITY_VIOLATION` | 409 |
| `TRANSACTION_NOT_FOUND` | 404 | `DATABASE_UNAVAILABLE` | 503 |

OpenAPI is served at `/v3/api-docs` with Swagger UI at `/swagger-ui.html`, both without a token. The frontend is expected to generate its client types from it. Since nginx does not proxy these paths, they stay internal in production.

### 5.3 Service layer

**Posting.** Every business transaction writes one `ledger_transactions` row and two `ledger_entries` rows. Deposits debit the system cash account `SYSTEM-CASH-AUD` and credit the customer; withdrawals do the reverse; transfers debit the source customer account and credit the target. A currency is supported only while its `SYSTEM-CASH-<CURRENCY>` account exists, so AUD is the only currency today. Entries are append-only: the entity is immutable, the repository has no update or delete, and a database trigger rejects `UPDATE` and `DELETE`. Mistakes are corrected by a reversal, which posts mirror entries and can happen at most once per transaction.

**Idempotency.** Every posting carries a caller-chosen `referenceId` of at most 64 characters, stored with a SHA-256 fingerprint of the transaction type, account ids, amount, currency and description. Amounts are compared by value, so `100.00` and `100.0000` match. A retry with the same `referenceId` and payload returns the original result, including the balance recorded at that time, and posts nothing. A reused `referenceId` with a different payload is refused with `409 IDEMPOTENCY_PAYLOAD_MISMATCH`. Concurrent duplicates queue on a transaction-scoped advisory lock keyed by the `referenceId`, so the first posts and the rest replay it.

**Locking.** Balance-changing operations lock accounts with `PESSIMISTIC_WRITE`. Transfers always lock the lower account id first, so opposing transfers cannot deadlock.

**Transactions.** Request transactions time out after 10 s (`spring.transaction.default-timeout`). Reconciliation is the exception and is described in [section 8](#8-reconciliation).

**Transfer response (done).** `TransferService` authorizes only the source account, because money leaving it is what needs permission; the target may belong to anyone. The response used to include `targetBalanceAfter`, both on first submission and on replay. Account ids are sequential and the minimum amount is 0.0001, so any customer could read every other customer's balance by sending them 0.0001 each. The response now carries the source balance only, on both paths and for every caller, administrators and owners of both accounts included. A caller allowed to see the target reads it with `GET /api/accounts/{id}`. Hiding the field in the frontend would not have fixed this. `AuthorizationIntegrationTest` asserts the field is absent on first submission and on replay, and `OpenApiDocumentationTest` asserts it is gone from the OpenAPI document.

### 5.4 Persistence

Spring Data JPA over Hibernate, with `ddl-auto=validate` so every schema change goes through Flyway, and `open-in-view=false` so query services fetch what they need explicitly. HikariCP waits at most 3 s for a connection, with a pool of 20.

### 5.5 Observability

Every series below is published at zero from startup, so the first real occurrence is a step that `increase()` and `rate()` can see. Tag values come from fixed sets.

| Metric | Type | Meaning |
| --- | --- | --- |
| `banking_transaction_errors_total{reason}` | counter | Money movement that did not complete, by error code |
| `banking_database_failures_total{reason}` | counter | Requests failed by the datastore: `connection`, `timeout`, `lock`, `other` |
| `banking_reconciliation_runs_total{outcome}` | counter | Completed runs, `clean` or `differences` |
| `banking_reconciliation_failures_total{cause}` | counter | Runs that threw instead of completing |
| `banking_reconciliation_differences` | gauge | Accounts the last run found disagreeing with their entries |
| `banking_reconciliation_last_success_timestamp` | gauge | Last completed run in epoch seconds; `0` means never |

Authentication and authorization refusals are not counted as transaction errors, and a datastore outage is counted once, as a database failure. The database failure classifier covers both `DataAccessException` and `TransactionException`, because an unreachable database raises the second.

## 6. Data

One PostgreSQL 17 instance holds two databases with separate users: `banking_ledger` for the ledger and `keycloak` for identity data.

| Table | Purpose |
| --- | --- |
| `accounts` | Customer and system accounts, materialized `balance`, `owner_subject` (indexed since V7) |
| `ledger_transactions` | One row per business transaction, with `reference_id` and `request_hash` |
| `ledger_entries` | Two rows per transaction; append-only trigger since V5 |
| `audit_logs` | Account and transaction events |
| `reconciliation_runs` | One row per reconciliation run, clean or not |
| `reconciliation_differences` | One row per account that disagreed in a run |

Money columns are `NUMERIC(19, 4)`. Requests accept at most 15 integer and 4 fractional digits, with a minimum of 0.0001, and the database rejects a negative account balance. `accounts.balance` is a materialized figure for fast, lock-safe reads; `ledger_entries` are the source of truth.

Flyway migrations V1 to V8 are applied by the app at startup. V7 added `owner_subject` and its index, so listing a customer's accounts needs no new migration. V8 created the two reconciliation tables. A `statement_timeout` of 30 s (`BANKING_STATEMENT_TIMEOUT_MS`) is set on every connection.

## 7. Client contract

The frontend depends on these behaviours. Several are planned changes, and all of them belong in the OpenAPI description and the tests, not only in this document.

**Money is a string (planned).** On `main`, the API reference shows request amounts as strings, but responses return them as JSON numbers. A JavaScript number holds about 15 to 17 significant digits, fewer than the 19 the ledger stores, so large values are already wrong after `JSON.parse`. Every amount and balance in every response becomes a string with up to 4 decimal places: balances, entries, deposits, withdrawals, transfers, reversals and reconciliation differences. Clients never convert money to `Number`; they use a decimal library for arithmetic and format only for display, without rounding to 2 places.

```json
{ "transactionId": 812, "amount": "250.0000", "sourceBalanceAfter": "1749.5000" }
```

**Retries.** A client generates a new `referenceId` for each new transaction and keeps it with the request. A timeout means the outcome is unknown, not that the transaction failed: the client shows the result as pending and retries with the same `referenceId` and the same payload. It generates a new id only after a confirmed outcome or when the user changes the transaction.

**Balances after posting.** A replay returns the balance recorded when the transaction first posted, which may no longer be the current balance. After any success or replay, the client reads the account again instead of displaying `balanceAfter` as current.

**Recipients.** A transfer names its target by `targetAccountId`, and a customer's account list contains only their own accounts. Paying another person by account number needs a recipient lookup endpoint that resolves a number without revealing the owner or balance. It is listed as optional and not designed yet.

**Reversal state.** Ledger entries do not say whether their transaction has been reversed. An admin screen that shows "reversed" or hides the reverse button needs that added to the entry response; until then the server's `REVERSAL_NOT_ALLOWED` is the only signal.

The frontend's acceptance criteria follow from this contract.

| Situation | Required behaviour |
| --- | --- |
| Request times out | Show "result pending", not "failed" |
| User or client retries | Reuse the original `referenceId` and payload |
| Success or replay | Re-read the account balance |
| Any money value | Keep as string; never round to 2 places |

## 8. Reconciliation

`ReconciliationScheduler` runs every 5 minutes after a 1-minute initial delay (`banking.reconciliation.interval=PT5M`, `initial-delay=PT1M`). It calls `ReconciliationService.reconcile()`, which deliberately runs without a transaction, and that calls `ReconciliationRunner.execute()`, which runs in its own transaction with a 60 s timeout (`banking.reconciliation.transaction-timeout`). Each statement inside it is still limited to 30 s by `statement_timeout`; the two bounds apply together.

The runner recomputes every account's balance from its entries, stores the run and any differences, and commits as it returns. Only after that commit does the service record metrics, because metrics live in memory and do not roll back; recording them inside the transaction would let a failed commit move the last-success timestamp. The query methods on the service use read-only transactions. `/actuator/health` reports `DOWN` while the last run found differences. Reconciliation reports drift and never repairs it, because rewriting a balance would destroy the evidence of whatever caused it.

## 9. Timeouts

Nothing waits indefinitely. The bounds nest, innermost first, so a request fails at the layer that knows most about what went wrong.

| Bound | Value | Limits |
| --- | --- | --- |
| `spring.datasource.hikari.connection-timeout` | 3 s | Waiting for a pooled connection |
| `spring.transaction.default-timeout` | 10 s | A request transaction |
| `banking.reconciliation.transaction-timeout` | 60 s | The reconciliation transaction |
| `statement_timeout` | 30 s | Any single statement, in the database |

## 10. Operations

### Monitoring and alerting

Prometheus scrapes `app:8080/actuator/prometheus` over the internal network and evaluates the rules in `ops/prometheus/alerts.yml`. Without Alertmanager, a firing alert is visible only in the Prometheus UI; adding Alertmanager to route alerts to email or Slack is optional.

| Alert | Fires when | Severity |
| --- | --- | --- |
| `BankingTransactionErrors` | Transaction errors average over 0.2/s for 10 minutes | warning |
| `BankingDatabaseFailures` | Any datastore failure in 5 minutes | critical |
| `BankingReconciliationDifferences` | The last run found a disagreeing account | critical |
| `BankingReconciliationStalled` | Nothing reconciled for 30 minutes, or ever | warning |
| `BankingReconciliationFailing` | Runs are being attempted and throwing | warning |
| `BankingLedgerDown` | The metrics endpoint cannot be scraped | critical |

### Backup and restore

`backup.sh` writes a `pg_dump` to a `.partial` file, reads the archive back to confirm it holds the ledger tables, and only then renames it. `restore.sh` checks the dump, restores it into a staging database, verifies that every balance matches its entries while the app keeps serving, then stops the app and swaps the databases by rename, keeping the old one. A rehearsed restore took 12 seconds with about one second of unavailability ([recovery rehearsal](../recovery-rehearsal.md)).

Both scripts handle only `banking_ledger` today. The planned change adds a dump of the `keycloak` database. Restoring one database to an older point than the other can leave accounts owned by subjects that no longer exist, so the two are backed up together and restored with that risk in mind. Sharing the PostgreSQL instance is compatible with `restore.sh`, which stops only the app and disconnects only the ledger and staging databases.

### Capacity

Throughput is roughly 700 to 800 requests per second from 5 to 100 concurrent clients, and latency grows with the clients added. The write path is the limit: every deposit and withdrawal locks the single `SYSTEM-CASH-AUD` row ([capacity report](../capacity-report.md)).

## 11. CI and verification

`.github/workflows/ci.yml` runs the full test suite against Testcontainers PostgreSQL, then builds the image. The planned additions are a frontend job and an end-to-end suite.

| Check | Status | What it proves |
| --- | --- | --- |
| Backend tests, image build | existing | Unit, slice and integration tests, including idempotent replay of a committed posting and retry after a rolled-back one |
| Frontend: lint, `tsc`, Vitest, build | planned | The SPA compiles and its units pass |
| E2E: real Keycloak login | planned | The token contract in [4.2](#42-token-contract) works end to end |
| E2E: customer calls an admin endpoint | planned | Refused with 403 using a real token |
| E2E: client times out after the server commits | planned | Retrying with the same `referenceId` and payload leaves exactly one transaction, and the UI goes from pending to confirmed |

The last check is not the same as the existing replay tests. Those call the service twice in sequence. The end-to-end version makes the client give up while the first request is still running, so the retry can arrive before the first commit and exercises the advisory-lock path through nginx.

## 12. Planned changes and implementation order

Work proceeds in this order: authentication and the balance leak first, then the money contract, then a minimal frontend loop, then administration. Deployment changes land alongside the step that first needs them.

| Step | Change | Done when |
| --- | --- | --- |
| 1 | HMAC decoder behind an explicit dev profile; secret moved to dev config | **Done.** An OIDC-only configuration starts, with a test |
| 1 | Keycloak with its own database, `realm-export.json`, mappers | **Done.** A real token carries `aud` and `ledger_roles` as in [4.2](#42-token-contract); checked by hand, automated in #32 |
| 1 | Production `banking.security.*`, `issuer-uri`, `jwk-set-uri`, `audiences` | **Done.** Customer gets 403 on an admin endpoint, administrator gets through |
| 1 | Transfer response without `targetBalanceAfter` | **Done.** Absent on first submission and on replay, with tests |
| 2 | Money as strings in every response | OpenAPI and tests updated; no amount is a JSON number |
| 3 | nginx with two server blocks; no host ports in production | Only 443 published; dev ports bound to `127.0.0.1` |
| 3 | SPA: login, account list, transfer, balance refresh | Acceptance criteria in [section 7](#7-client-contract) pass |
| 3 | CI frontend job and end-to-end checks | All checks in [section 11](#11-ci-and-verification) green |
| 3 | Backups include the `keycloak` database | A restore rehearsal covers both |
| 3 | Development data reset or migrated to Keycloak subjects | No account owned by a development identity remains |
| 4 | Admin screens: freeze, reversal, reconciliation | Built on the existing admin endpoints |
| optional | CORS, Alertmanager, entry reversal status, recipient lookup | Only if the condition in [section 13](#13-optional-items) applies |

## 13. Optional items

**CORS** is needed only if the SPA and API are ever served from different origins. **Alertmanager** is needed once alerts must reach someone outside the Prometheus UI. **Entry reversal status** is needed if admin screens must show reversals reliably. **Recipient lookup** is needed if customers pay other people by account number. Whether to expose OpenAPI and Swagger UI outside the internal network in production is also open; this design keeps them internal.

## Related documents

[README](../../README.md) · [API reference](../api.md) · [Capacity report](../capacity-report.md) · [Recovery rehearsal](../recovery-rehearsal.md) · [Architecture diagram source](architecture.drawio)
