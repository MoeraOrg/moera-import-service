# Import Flow Control Design

**Status:** Approved design  
**Date:** 2026-08-28  
**Canonical architecture:** `doc/architecture/import-service.md`

## Goal

Keep polling and Moera delivery bounded under a large source backlog, prevent a live long-running Telegram poll from
being reclaimed, pace new Moera postings per destination, make the safe initial Telegram import small by default, and
report stale poll completion accurately.

This design does not delete existing messages, publications, or Moera postings. Existing queued publications remain
eligible for delivery under the new concurrency and rate limits.

## Decisions

- Use a hybrid design: local per-workflow admission control plus PostgreSQL-coordinated leases and create-rate state.
- Apply independent concurrency limits to channel, publication-builder, publication-media, and publication workflows.
- Renew only a running channel `POLL` lease. Access and subscription operations remain bounded by their original lease.
- Limit only `MoeraService.createPost`; updates, deletes, story changes, and media uploads are not rate-limited.
- Maintain independent rate state per destination while applying the same configured rate to every destination.
- Default to ten creates per minute per destination, evenly spaced by a six-second minimum interval.
- Default an initial Telegram poll to the newest twenty unique Telegram messages. Full history is opt-in when the
  channel is created.
- Treat a rejected `MessageRepository.ingestPoll(...)` completion as `stale_claim`, never as success.

## Per-workflow admission control

A focused `WorkerDispatcher` owns the shared virtual-thread executor and four independent permit pools. Defaults are:

```text
channel             2
publication-builder 2
publication-media   4
publication         4
```

All limits are positive typed `moera.workers` properties. Each scheduler reserves up to the smaller of its configured
concurrency and `batch-size` before it calls a repository claim/find method. The repository therefore receives only
the number of tasks that can start immediately. Every claimed item consumes one reserved permit until its processor
returns; the dispatch wrapper releases it in `finally`. Any reservation not matched by a returned claim is released
immediately.

Reservation is non-blocking. If no permit is available, the scheduler does not query or claim its work queue on that
tick. No claimed work waits in an unbounded executor submission queue. The shared executor still creates virtual
threads because database, network, and file work is blocking.

`batch-size` remains a database batching ceiling and is not a concurrency setting. Publication-builder work uses the
same admission protocol even though it uses row locking rather than leases.

Shutdown first disables new claims and reservations, then waits only for the bounded set of active tasks before the
existing grace-period cancellation behavior.

## Poll lease renewal

`ChannelRepository` adds a conditional poll-renewal operation conceptually equivalent to:

```java
boolean renewPollLease(long channelId, UUID processingToken, Duration lease);
```

The update matches `channel_id`, `processing_operation = POLL`, the current `processing_token`, and an unexpired
`processing_until`. It sets `processing_until` from PostgreSQL current time plus the configured channel lease. It does
not change attempts or any poll scheduling state.

`PollLeaseHeartbeat` starts on a virtual thread immediately before the adapter poll and stays active through the final
`ingestPoll(...)` transaction. It attempts renewal every `channelLease / 3`. A transient database exception is logged
and retried on the next heartbeat; one failed renewal does not immediately surrender the lease. The heartbeat tracks
the last known successful expiry. If renewal cannot succeed before that expiry, or the conditional update returns
false, it marks the lease lost and interrupts the poll worker on a best-effort basis. A result produced afterward is
still protected by the existing token check and cannot be persisted.

The heartbeat is always cancelled in `finally`. It owns no PostgreSQL transaction while Telegram work is running.
Metrics and structured logs distinguish successful renewal, renewal failure, lease loss, and stale completion without
using channel IDs or processing tokens as metric labels.

`ChannelProcessor` derives its final result from conditional completion. A false `ingestPoll(...)` return produces
`stale_claim` in the source-operation log and metric. The processor never reports that result as success.

## Destination create rate limit

Flyway migration `V003` adds this nullable durable field:

```text
destinations.next_create_at timestamptz
```

`NULL` means that a create slot is immediately available. jOOQ is regenerated from all production Flyway migrations.

Typed delivery configuration is:

```yaml
moera:
  delivery:
    create-rate:
      count: 10
      period: 1m
```

Both values must be positive. The minimum interval is the period divided by the count, rounded upward when necessary
so configuration cannot exceed the requested rate.

Immediately before a non-empty first posting is created, a concrete persistence component locks the destination row
and uses PostgreSQL current time to acquire the slot:

- when `next_create_at` is null or due, set it to database current time plus the minimum interval, commit, and call
  `MoeraService.createPost`;
- when it is in the future, return that timestamp without changing it or calling Moera.

On rate deferral, `PublicationRepository` conditionally clears the publication lease, sets `next_attempt_at` to the
returned destination timestamp, and reverses the attempts increment made by that claim because no delivery attempt
started. It does not set `last_error` or increment a failure metric. The structured result is `rate_limited`.

The slot is consumed before the external call. Temporary and outcome-unknown create results therefore still consume
one interval, which is conservative and prevents a failing destination from being flooded. Existing-post update,
delete, story update, capability lookup, and publication-media operations bypass the limiter.

Destination-row locking makes slot acquisition safe across workers and JVMs. Different destination rows never block
each other's rate state. A restart preserves `next_create_at`.

## Initial Telegram import scope

The source boundary adds:

```java
enum InitialImportMode {
    RECENT,
    FULL
}
```

Channel resolution accepts the mode in addition to the source URI. The Telegram adapter stores its representation in
`source_data`, keeping source-state interpretation behind the adapter. A missing or unrecognized stored value is
treated as `RECENT`; this makes pre-existing channels without the field safe when their waterline is null.

`moera.telegram.initial-history-count` is a positive integer with default `20`.

When the Telegram waterline is null:

- `RECENT` paginates backward only until twenty unique raw Telegram messages have been scanned, or history ends;
- `FULL` retains the existing scan-to-history-start behavior;
- mapped results remain chronological and deliberately skipped source messages still count toward the scan limit and
  waterline;
- the returned waterline is the newest scanned Telegram message ID and is persisted only by successful poll ingest.

Once the waterline exists, both modes use the existing forward scan plus look-behind. The initial mode does not affect
ordinary polling.

The host-local create-channel request becomes:

```json
{
  "sourceType": "TELEGRAM",
  "sourceUri": "https://t.me/example",
  "fullHistory": true
}
```

`fullHistory` is optional and defaults to false. The CLI exposes:

```text
moera-import-admin channel add SOURCE_URI [--full-history]
```

The mode is immutable after channel creation. Re-adding a duplicate channel remains a conflict and does not modify the
existing channel.

## Validation, observability, and errors

Application startup rejects non-positive workflow concurrency, lease, rate count/period, or initial-history count.
Structured operation results add `stale_claim`, `rate_limited`, and lease-renewal outcomes. Metrics remain
low-cardinality and never label channel IDs, destination IDs, node names, publication IDs, or processing tokens.

A rate deferral and a stale claim are expected coordination outcomes, not failures. A heartbeat database exception is
an operational warning. If the lease is eventually lost, the original worker cannot persist through conditional
completion and later reclaim proceeds normally.

The service does not automatically delete the existing backlog or already delivered Moera postings. Such cleanup is a
separate destructive operation requiring explicit operator scope.

## Testing

Implementation follows red-green-refactor and adds:

- unit tests for permit reservation, unused reservation release, task-finally release, and independent workflow caps;
- scheduler tests proving a zero-capacity workflow performs no claim and claim limits never exceed reserved capacity;
- real-PostgreSQL tests for poll renewal, token mismatch, renewal/reclaim races, and reclaim after heartbeat stops;
- channel-processor tests for heartbeat lifetime through ingest and `stale_claim` reporting;
- Flyway/schema tests for `destinations.next_create_at`;
- real-PostgreSQL tests proving only one concurrent create slot is acquired for one destination and separate
  destinations acquire independently;
- publication-processor tests proving only first create is limited, rate deferral calls no Moera method, and deferral
  neither records a failure nor inflates attempts;
- Telegram polling tests for the recent twenty-message boundary, short history, skipped messages, full history, and
  normal waterline/look-behind behavior;
- administration API tests for the omitted default and `fullHistory=true`, plus CLI argument parsing;
- full `./mvnw clean verify` with production Flyway migrations and Testcontainers PostgreSQL.

