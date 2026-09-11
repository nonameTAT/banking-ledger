# Capacity report

What the ledger sustains, measured rather than estimated. Re-run with
`ops/load/ledger-load.js`; the method is at the bottom.

## Environment

These numbers describe this hardware and this deployment shape. They are a
baseline to compare against after a change, not a promise about production.

| | |
| --- | --- |
| Host | 20 vCPU, 7 GB RAM, WSL2 (kernel 6.6.87.2) |
| Deployment | Docker Compose: one application container, one PostgreSQL 17.10 container |
| Runtime | Temurin 25.0.4, Spring Boot 4.1 |
| Connection pool | HikariCP, max 20 |
| Load generator | k6, on the same host as the service |

The load generator sharing a host with the service is the largest caveat here:
it competes for the same CPU, so these figures understate what the service would
do with the generator elsewhere.

## Write mix

One iteration is a deposit, a transfer, an account read, and a page of ledger
entries, run against a pool of 8 funded accounts. Deposits and transfers are the
expensive part: each takes row locks, writes a transaction, two ledger entries,
and an audit row.

| Concurrency | Throughput | median | p95 | p99 | Failures |
| --- | --- | --- | --- | --- | --- |
| 10 VUs | 665 req/s | 6.4 ms | 42.6 ms | 55.9 ms | 0 / 35,844 |
| 25 VUs | 790 req/s | 11.6 ms | 103.3 ms | 121.8 ms | 0 / 41,024 |
| 50 VUs | 746 req/s | 40.9 ms | 133.9 ms | 158.7 ms | 0 / 40,380 |
| 100 VUs | 698 req/s | 109.2 ms | 229.8 ms | 290.2 ms | 0 / 36,436 |

**Throughput peaks near 25 concurrent clients at about 790 requests per second.**
Past that it stops rising and latency grows roughly in proportion to the load
added: at 100 VUs the service is doing slightly less work than at 25 while each
caller waits four times as long. That is a queue forming, not capacity being
used. Sizing this deployment above ~25 concurrent writers buys nothing.

Nothing failed at any level, which is the other half of the result: the ceiling
shows up as waiting, not as errors.

## Where the ceiling comes from

The same shape with the writes removed, at the same concurrency:

| 50 VUs | Throughput | median | p95 | p99 |
| --- | --- | --- | --- | --- |
| Write mix | 746 req/s | 40.9 ms | 133.9 ms | 158.7 ms |
| Reads only | 1,304 req/s | 27.0 ms | 69.0 ms | 95.3 ms |

Reads sustain 1.75x the throughput at half the latency, so the limit is not CPU,
the connection pool, or the network. It is the write path.

Every deposit and withdrawal posts against the single seeded system cash
account, `SYSTEM-CASH-AUD`, and takes a row lock on it. That one row serialises
cash movement across the whole service regardless of how many accounts or
clients exist. It is a deliberate consequence of double-entry bookkeeping
against one cash account, and it is the first thing to address if this ceiling
ever matters: the usual approach is to shard the cash account into several rows
and pick one per transaction, so contention spreads.

## A defect this found

The first run failed 30.74% of requests. All of them were
`ObjectOptimisticLockingFailureException`, answered as `503` and counted as
`other` database failures.

The cause was not load. The ownership check added for authentication loaded the
account entity before the balance-changing code took its pessimistic lock, so
Hibernate served that cached copy, and its by-then-stale `@Version`, to the
locking read. Under contention another transaction committed in between and the
version check failed at flush.

It had been invisible because **every concurrency test in the suite ran as an
administrator, and an administrator skips the ownership check entirely.** The
contended customer path had never been exercised. The same load with an
administrator token passed 25,984 requests without a single failure, which is
what confirmed it.

The check now reads ownership through a projection instead of loading the
entity, and `FailureHandlingIntegrationTest` covers the contended path as the
account's owner. Both figures above are from after the fix.

## Running it

```bash
docker compose up -d --build

TOKEN=$(TOKEN_TTL_SECONDS=7200 scripts/dev-token.sh alice)
# Create and fund a few accounts, then pass their ids:
docker run --rm --network host -v "$PWD/ops/load:/scripts:ro" \
  -e BASE_URL=http://localhost:8080 \
  -e TOKEN="$TOKEN" \
  -e ACCOUNT_IDS=2,3,4,5,6,7,8,9 \
  -e VUS=25 -e RAMP_UP=10s -e HOLD=30s \
  grafana/k6 run /scripts/ledger-load.js
```

The script fails the run if any deposit is rejected or p95 exceeds one second, so
it is usable as a check and not only as a measurement.
