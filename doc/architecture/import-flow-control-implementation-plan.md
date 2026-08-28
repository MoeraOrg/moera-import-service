# Import Flow Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound every worker workflow, renew long Telegram poll leases, pace new Moera postings per destination, default initial Telegram imports to twenty messages, and report stale poll completion accurately.

**Architecture:** A local `WorkerDispatcher` reserves per-workflow capacity before PostgreSQL claims and executes only bounded work on the shared virtual-thread executor. PostgreSQL remains the correctness boundary for poll heartbeat renewal and durable per-destination create slots. Telegram persists its selected initial-import mode in source JSONB, while the localhost API exposes a simple opt-in full-history flag.

**Tech Stack:** Java 25, Spring Boot/Spring Framework, Maven, PostgreSQL 18 via Testcontainers, Flyway, jOOQ, TDLight, JUnit 5, AssertJ, Micrometer, Python 3 standard library.

**Spec:** `doc/architecture/import-flow-control-design.md`

## Global Constraints

- Read `doc/architecture/import-service.md` and this plan before each task; the architecture is authoritative.
- Use Java 25 and Spring with virtual threads for blocking database, network, and file work.
- PostgreSQL durable state is defined only by forward Flyway migrations; regenerate jOOQ from those migrations.
- Keep `DSLContext`, generated tables/records, and jOOQ DSL inside concrete persistence classes.
- Do not hold a PostgreSQL transaction across Telegram, Moera, filesystem, or rate-wait work.
- Do not add repository interfaces, ORM/Hibernate, Reactor, a generic job queue, or a durable event bus.
- Do not log message bodies, invite links, credentials, tokens, verification codes, or other forbidden sensitive data.
- Metrics must use low-cardinality labels and must not label channel, destination, publication, media, node, or token IDs.
- Setters and getters only copy values; use field injection and method references where applicable.
- Follow TDD for every behavior: write the failing test, observe the expected failure, implement minimally, then rerun.
- Repository and concurrency tests use production Flyway migrations on real PostgreSQL via Testcontainers, never H2.
- Existing queued or delivered publications are not deleted or rewritten by this work.

---

## File map

New focused production files:

```text
src/main/java/org/moera/importer/source/InitialImportMode.java
src/main/java/org/moera/importer/scheduling/WorkerWorkflow.java
src/main/java/org/moera/importer/scheduling/WorkerDispatcher.java
src/main/java/org/moera/importer/application/PollLeaseHeartbeat.java
src/main/java/org/moera/importer/config/CreateRateProperties.java
src/main/java/org/moera/importer/persistence/DestinationCreateSlot.java
src/main/resources/db/migration/V003__destination_create_rate.sql
```

New focused tests:

```text
src/test/java/org/moera/importer/scheduling/WorkerDispatcherTest.java
src/test/java/org/moera/importer/application/PollLeaseHeartbeatTest.java
src/test/java/org/moera/importer/persistence/DestinationCreateRateLimitTest.java
scripts/test_moera_import_admin.py
```

Existing schedulers retain claim policy; `WorkerDispatcher` only controls admission and dispatch. `PollLeaseHeartbeat`
owns poll renewal lifecycle. `DestinationRepository` owns destination-row slot locking, and `PublicationRepository`
owns conditional rate deferral of a publication claim.

---

### Task 1: Add bounded and full Telegram initial-import modes

**Files:**
- Create: `src/main/java/org/moera/importer/source/InitialImportMode.java`
- Modify: `src/main/java/org/moera/importer/source/SourceAdapter.java`
- Modify: `src/main/java/org/moera/importer/source/telegram/TelegramSourceAdapter.java`
- Modify: `src/main/java/org/moera/importer/config/TelegramProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/org/moera/importer/source/telegram/TelegramPollingTest.java`
- Modify: `src/test/java/org/moera/importer/source/telegram/TelegramSourceAdapterTest.java`
- Modify: `src/test/java/org/moera/importer/admin/AdministrationApiTest.java`
- Modify: `src/test/java/org/moera/importer/ImportPipelineTest.java`
- Modify: `src/test/java/org/moera/importer/source/SourceAdapterRegistryTest.java`
- Modify: `src/test/java/org/moera/importer/application/ChannelProcessorTest.java`
- Modify: `src/test/java/org/moera/importer/application/PublicationBuilderTest.java`
- Modify: `src/test/java/org/moera/importer/application/PublicationMediaProcessorTest.java`

**Interfaces:**
- Produces: `InitialImportMode { RECENT, FULL }`.
- Produces: `ChannelResolution SourceAdapter.resolveChannel(URI sourceUri, InitialImportMode initialImportMode)`.
- Produces: `int TelegramProperties.getInitialHistoryCount()` and copying setter.
- Persists: Telegram `source_data.initialImportMode` as the enum name.
- Consumes later: admin channel creation in Task 2.

- [x] **Step 1: Write failing polling and resolution tests**

Add these focused cases to `TelegramPollingTest` and `TelegramSourceAdapterTest`:

```java
@Test
void recentInitialPollStopsAfterConfiguredUniqueMessageCount() {
    properties.setInitialHistoryCount(3);
    client.pages.add(page(5, message(5, "five"), message(4, "four"), message(3, "three")));

    PollResult result = adapter(new TelegramMessageMapper()).poll(channel(null, "RECENT"));

    assertThat(result.messages()).extracting(SourceMessage::sourceId)
        .containsExactly("3", "4", "5");
    assertThat(client.requestedLimits).containsExactly(3);
    assertThat(result.waterline().path("messageId").asLong()).isEqualTo(5);
}

@Test
void fullInitialPollContinuesToHistoryStart() {
    properties.setInitialHistoryCount(2);
    client.pages.add(page(4, message(4, "four"), message(3, "three")));
    client.pages.add(page(4, message(2, "two"), message(1, "one")));
    client.pages.add(page(4));

    PollResult result = adapter(new TelegramMessageMapper()).poll(channel(null, "FULL"));

    assertThat(result.messages()).extracting(SourceMessage::sourceId)
        .containsExactly("1", "2", "3", "4");
}

@Test
void channelResolutionPersistsRequestedInitialImportMode() {
    ChannelResolution result = adapter.resolveChannel(
        URI.create("https://t.me/news"), InitialImportMode.FULL
    );

    assertThat(result.sourceData().path("initialImportMode").asText()).isEqualTo("FULL");
}
```

Also prove that a deliberately skipped Telegram message counts toward the recent limit and that missing or unknown
`initialImportMode` behaves as `RECENT`.

- [x] **Step 2: Run the focused tests and verify RED**

Run:

```bash
./mvnw -Dtest=TelegramPollingTest,TelegramSourceAdapterTest test
```

Expected: compilation fails because `InitialImportMode`, the two-argument `resolveChannel`, and
`initialHistoryCount` do not exist.

- [x] **Step 3: Implement the source contract and bounded pagination**

Create the enum with no methods:

```java
public enum InitialImportMode {
    RECENT,
    FULL
}
```

Change the source contract to the exact two-argument method. Update every fake adapter mechanically to accept the
mode. In `TelegramSourceAdapter.resolveChannel`, require the mode and put its name into the `ObjectNode` used for both
public and invite resolutions.

Add this property with copying accessors and startup validation in `TelegramProperties`:

```java
private int initialHistoryCount = 20;

@PostConstruct
void validate() {
    if (initialHistoryCount <= 0) {
        throw new IllegalStateException("Telegram initial history count must be positive");
    }
}
```

Add `initial-history-count: ${TELEGRAM_INITIAL_HISTORY_COUNT:20}` to `application.yml`.

In `poll`, derive `initialLimit` only when `previousWaterline == 0` and stored mode is not `FULL`. Request
`min(historyPageSize, initialLimit - scanned.size())`, count unique message IDs before mapping, and stop as soon as the
limit is reached. Preserve the existing full scan, chronological mapping, look-behind, skipped-message waterline, and
no-progress behavior.

- [x] **Step 4: Run the Telegram tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=TelegramPollingTest,TelegramSourceAdapterTest,SourceAdapterRegistryTest test
```

Expected: PASS; recent tests make one bounded history request and full-history tests still reach the empty page.

- [x] **Step 5: Commit Task 1**

```bash
git add src/main/java/org/moera/importer/source src/main/java/org/moera/importer/config/TelegramProperties.java src/main/java/org/moera/importer/source/telegram/TelegramSourceAdapter.java src/main/resources/application.yml src/test/java
git commit -m "feat: bound initial Telegram history"
```

---

### Task 2: Expose full-history opt-in through the localhost API and CLI

**Files:**
- Modify: `src/main/java/org/moera/importer/admin/AdministrationModels.java`
- Modify: `src/main/java/org/moera/importer/admin/AdministrationService.java`
- Modify: `src/test/java/org/moera/importer/admin/AdministrationApiTest.java`
- Modify: `scripts/moera-import-admin`
- Create: `scripts/test_moera_import_admin.py`

**Interfaces:**
- Consumes: `InitialImportMode` and the two-argument `SourceAdapter.resolveChannel` from Task 1.
- Produces: `ChannelCreate(SourceType sourceType, String sourceUri, boolean fullHistory)`.
- Produces: CLI syntax `channel add SOURCE_URI [--full-history]`.

- [x] **Step 1: Write failing API and CLI tests**

In `AdministrationApiTest`, make the fake adapter record `InitialImportMode resolvedMode` and add:

```java
@Test
void createChannelDefaultsToRecentHistory() throws Exception {
    mockMvc.perform(post("/api/channels")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"sourceType":"TELEGRAM","sourceUri":"https://t.me/news"}
            """))
        .andExpect(status().isCreated());

    assertThat(sourceAdapter.resolvedMode).isEqualTo(InitialImportMode.RECENT);
}

@Test
void createChannelCanRequestFullHistory() throws Exception {
    mockMvc.perform(post("/api/channels")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"sourceType":"TELEGRAM","sourceUri":"https://t.me/news","fullHistory":true}
            """))
        .andExpect(status().isCreated());

    assertThat(sourceAdapter.resolvedMode).isEqualTo(InitialImportMode.FULL);
}
```

Create `scripts/test_moera_import_admin.py` with standard-library `unittest`. Load the extensionless script with
`importlib.machinery.SourceFileLoader`, `importlib.util.spec_from_loader`, and `module_from_spec`, then call
`loader.exec_module(module)`. Assert that omitted `--full-history` parses false and the flag parses true, and use a
recording client to assert the POST body contains `"fullHistory": true`.

- [x] **Step 2: Run both test boundaries and verify RED**

Run:

```bash
./mvnw -Dtest=AdministrationApiTest test
python3 -m unittest scripts/test_moera_import_admin.py
```

Expected: Java compilation/request assertions fail because `ChannelCreate` has no flag; Python assertions fail because
the parser and request body have no `full_history` value.

- [x] **Step 3: Implement API mapping and CLI option**

Change the request record exactly to:

```java
public record ChannelCreate(SourceType sourceType, String sourceUri, boolean fullHistory) {
}
```

Map the flag before the external resolution call:

```java
InitialImportMode initialImportMode = request.fullHistory()
    ? InitialImportMode.FULL
    : InitialImportMode.RECENT;
resolution = sourceAdapter.resolveChannel(sourceUri, initialImportMode);
```

Add `channel_add.add_argument("--full-history", action="store_true", ...)` and always send
`"fullHistory": args.full_history` in the POST JSON. Do not print or log the source URI in new diagnostics.

- [x] **Step 4: Run API and CLI tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=AdministrationApiTest test
python3 -m unittest scripts/test_moera_import_admin.py
python3 scripts/moera-import-admin channel add --help
```

Expected: PASS; help displays `--full-history`.

- [x] **Step 5: Commit Task 2**

```bash
git add src/main/java/org/moera/importer/admin src/test/java/org/moera/importer/admin scripts
git commit -m "feat: add full-history channel option"
```

---

### Task 3: Add per-workflow admission control before claims

**Files:**
- Create: `src/main/java/org/moera/importer/scheduling/WorkerWorkflow.java`
- Create: `src/main/java/org/moera/importer/scheduling/WorkerDispatcher.java`
- Create: `src/test/java/org/moera/importer/scheduling/WorkerDispatcherTest.java`
- Modify: `src/main/java/org/moera/importer/config/WorkerProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/org/moera/importer/scheduling/ChannelScheduler.java`
- Modify: `src/main/java/org/moera/importer/scheduling/PublicationBuilderScheduler.java`
- Modify: `src/main/java/org/moera/importer/scheduling/PublicationMediaScheduler.java`
- Modify: `src/main/java/org/moera/importer/scheduling/PublicationScheduler.java`
- Modify: `src/test/java/org/moera/importer/scheduling/SchedulerTest.java`
- Modify: `src/main/java/org/moera/importer/observability/ImportMetrics.java`

**Interfaces:**
- Produces: `WorkerWorkflow { CHANNEL, PUBLICATION_BUILDER, PUBLICATION_MEDIA, PUBLICATION }`.
- Produces: `WorkerDispatcher.Reservation reserve(WorkerWorkflow workflow, int maximum)`.
- Produces: `int WorkerDispatcher.available(WorkerWorkflow workflow)` for gauges and deterministic tests.
- Produces: `int Reservation.capacity()`, `void Reservation.dispatch(Runnable task)`, and idempotent `close()` that
  releases only unused reserved permits.
- Produces WorkerProperties copying accessors for four concurrency values with defaults 2, 2, 4, 4.

- [x] **Step 1: Write failing dispatcher tests**

Cover these behaviors in `WorkerDispatcherTest` using a deterministic queued executor:

```java
@Test
void reservesOnlyAvailableCapacityAndReleasesUnusedSlots() {
    QueueExecutorService executor = new QueueExecutorService();
    WorkerDispatcher dispatcher = dispatcher(executor, 2, 2, 4, 4);
    try (WorkerDispatcher.Reservation first = dispatcher.reserve(CHANNEL, 10)) {
        assertThat(first.capacity()).isEqualTo(2);
        first.dispatch(() -> {
        });
    }
    assertThat(dispatcher.available(CHANNEL)).isEqualTo(1);
    executor.runNext();
    assertThat(dispatcher.available(CHANNEL)).isEqualTo(2);
}

@Test
void workflowsHaveIndependentCapacity() {
    try (WorkerDispatcher.Reservation channel = dispatcher.reserve(CHANNEL, 10);
         WorkerDispatcher.Reservation media = dispatcher.reserve(PUBLICATION_MEDIA, 10)) {
        assertThat(channel.capacity()).isEqualTo(2);
        assertThat(media.capacity()).isEqualTo(4);
    }
}
```

Add a nested `QueueExecutorService extends AbstractExecutorService` whose `execute` enqueues commands and whose
`runNext()` removes and runs one command. Also prove task exceptions and rejected executor submissions release their
transferred permit exactly once.

- [x] **Step 2: Run dispatcher tests and verify RED**

Run:

```bash
./mvnw -Dtest=WorkerDispatcherTest test
```

Expected: compilation fails because `WorkerWorkflow` and `WorkerDispatcher` do not exist.

- [x] **Step 3: Implement properties, permit reservation, and active-task gauges**

Add these defaults to `WorkerProperties`:

```java
private int channelConcurrency = 2;
private int publicationBuilderConcurrency = 2;
private int publicationMediaConcurrency = 4;
private int publicationConcurrency = 4;
```

Add copying accessors and a `@PostConstruct validate()` that rejects any non-positive concurrency, non-positive
`batchSize`, or non-positive channel/publication-media/publication lease. Add corresponding YAML keys.

Implement one fair `Semaphore` per workflow. `reserve` uses only `tryAcquire`, never blocks, and returns zero capacity
when exhausted. `Reservation.dispatch` submits a wrapper that releases one transferred permit in `finally`; if submit
throws, release it before rethrowing. `close` releases the still-unassigned permits.

Register `worker_active_tasks` gauges through `ImportMetrics`, tagged only by the lower-case workflow name. Gauge value
is configured limit minus available permits.

- [x] **Step 4: Write failing scheduler admission tests**

Replace direct executor injection in `SchedulerTest` with a recording dispatcher or real dispatcher. Add assertions:

```java
assertThat(channelRepository.requestedLimits).containsExactly(2, 1);
assertThat(publicationRepository.requestedLimit).isEqualTo(4);
```

Exhaust a workflow before `run()` and assert its repository receives no claim/find invocation. Preserve channel
priority ACCESS → SUBSCRIPTION → POLL inside the reserved capacity.

- [x] **Step 5: Run scheduler tests and verify RED**

Run:

```bash
./mvnw -Dtest=SchedulerTest test
```

Expected: tests fail while schedulers still claim `batch-size` and submit directly to `ExecutorService`.

- [x] **Step 6: Route all schedulers through reservations**

Use this shape in every scheduler:

```java
try (WorkerDispatcher.Reservation reservation = workerDispatcher.reserve(
    WorkerWorkflow.PUBLICATION, workerProperties.getBatchSize()
)) {
    if (reservation.capacity() == 0) {
        return;
    }
    publicationRepository.claimReady(
        reservation.capacity(), workerProperties.getPublicationLease()
    ).forEach(claim -> reservation.dispatch(() -> publicationProcessor.process(claim)));
}
```

Apply the equivalent workflow value to channel, builder, and media schedulers. Remove their injected
`ExecutorService`; keep startup/readiness gates before reservation.

- [x] **Step 7: Run dispatcher, scheduler, and shutdown tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=WorkerDispatcherTest,SchedulerTest,WorkerExecutorConfigurationTest test
```

Expected: PASS with no repository call when capacity is zero and no permit leak after task completion.

- [x] **Step 8: Commit Task 3**

```bash
git add src/main/java/org/moera/importer/scheduling src/main/java/org/moera/importer/config/WorkerProperties.java src/main/java/org/moera/importer/observability/ImportMetrics.java src/main/resources/application.yml src/test/java/org/moera/importer/scheduling
git commit -m "feat: bound worker concurrency"
```

---

### Task 4: Renew active poll leases and report stale completion

**Files:**
- Create: `src/main/java/org/moera/importer/application/PollLeaseHeartbeat.java`
- Create: `src/test/java/org/moera/importer/application/PollLeaseHeartbeatTest.java`
- Modify: `src/main/java/org/moera/importer/persistence/ChannelRepository.java`
- Modify: `src/main/java/org/moera/importer/application/ChannelProcessor.java`
- Modify: `src/main/java/org/moera/importer/observability/ImportMetrics.java`
- Modify: `src/test/java/org/moera/importer/persistence/LeaseConcurrencyTest.java`
- Modify: `src/test/java/org/moera/importer/application/ChannelProcessorTest.java`

**Interfaces:**
- Produces: `boolean ChannelRepository.renewPollLease(long channelId, UUID token, Duration lease)`.
- Produces: `PollLeaseHeartbeat.Guard PollLeaseHeartbeat.start(ChannelClaim claim)`.
- Produces: `boolean Guard.leaseLost()` and idempotent `Guard.close()`.
- Consumes: shared virtual-thread executor, `WorkerProperties.channelLease`, `Clock`, `ChannelRepository`, and
  `ImportMetrics`.

- [x] **Step 1: Write failing repository renewal tests**

Add to `LeaseConcurrencyTest`:

```java
@Test
void matchingLivePollTokenExtendsLeaseWithoutChangingAttempts() {
    insertDuePoll();
    ChannelClaim claim = channelRepository.claimPolls(1, Duration.ofSeconds(30)).getFirst();
    OffsetDateTime before = dslContext.selectFrom(CHANNELS).fetchSingle().getProcessingUntil();

    boolean renewed = channelRepository.renewPollLease(
        claim.channel().id(), claim.processingToken(), Duration.ofMinutes(5)
    );

    var channel = dslContext.selectFrom(CHANNELS).fetchSingle();
    assertThat(renewed).isTrue();
    assertThat(channel.getProcessingUntil()).isAfter(before);
    assertThat(channel.getPollAttempts()).isEqualTo(claim.attempts());
}
```

Add separate tests that a wrong token and an already expired lease return false, and a concurrent reclaim cannot be
overwritten by the old renewal.

- [x] **Step 2: Run repository test and verify RED**

Run:

```bash
./mvnw -Dtest=LeaseConcurrencyTest test
```

Expected: compilation fails because `renewPollLease` does not exist.

- [x] **Step 3: Implement the conditional PostgreSQL renewal**

Use a single jOOQ update with these predicates:

```text
channels.id = channelId
channels.processing_operation = POLL
channels.processing_token = token
channels.processing_until > PostgreSQL current timestamp
```

Set only `processing_until = PostgreSQL current timestamp + lease` and `updated_at`. Return `updated == 1`; do not
alter attempts, scheduling, error, operation, or token.

- [x] **Step 4: Write failing heartbeat and processor tests**

In `PollLeaseHeartbeatTest`, use a short lease and latches to prove renewal runs on a virtual thread, transient
repository failure is retried before expiry, false renewal marks the guard lost and interrupts the owner, and `close`
stops further renewal.

In `ChannelProcessorTest`, make `RecordingMessageRepository.ingestPoll` return a configurable boolean. Add:

```java
@Test
void reportsStaleClaimWhenPollIngestRejectsToken() {
    messageRepository.pollAccepted = false;
    sourceAdapter.pollResult = new PollResult(
        List.of(), JsonNodeFactory.instance.objectNode().put("messageId", 42)
    );

    channelProcessor.process(claim(ChannelOperation.POLL));

    assertThat(meterRegistry.get("source_operations_total")
        .tags("source", "telegram", "operation", "poll", "result", "stale_claim")
        .counter().count()).isEqualTo(1);
}
```

Use a recording heartbeat guard to prove it is still open while `ingestPoll` executes and closes afterward.

- [x] **Step 5: Run heartbeat/processor tests and verify RED**

Run:

```bash
./mvnw -Dtest=PollLeaseHeartbeatTest,ChannelProcessorTest test
```

Expected: compilation/assertion failure because heartbeat is absent and `ChannelProcessor` always reports success.

- [x] **Step 6: Implement heartbeat lifecycle and conditional processor results**

`PollLeaseHeartbeat.start` records the owner thread and last-known local expiry, submits a heartbeat loop to the shared
virtual executor, and returns a guard. The interval is `channelLease.dividedBy(3)` with a one-nanosecond lower bound.
On repository exception, record `failed` and retry next interval. On false renewal or reaching last-known expiry,
atomically mark lost, record `lost`, and interrupt the owner best-effort.

Wrap only `POLL` with the guard and keep it open through `ingestPoll`. If polling throws while the guard is lost, return
`false` instead of scheduling a source retry. Change access/subscription/poll helper methods to return their concrete
conditional completion boolean, then set final result to `success` or `stale_claim`. Preserve temporary/permanent
failure handling when the heartbeat has not lost the lease.

```java
private boolean poll(ChannelClaim claim, SourceAdapter sourceAdapter) {
    try (PollLeaseHeartbeat.Guard guard = pollLeaseHeartbeat.start(claim)) {
        PollResult pollResult;
        try {
            pollResult = sourceAdapter.poll(claim.channel());
        } catch (RuntimeException e) {
            if (guard.leaseLost()) {
                return false;
            }
            throw e;
        }
        if (guard.leaseLost()) {
            return false;
        }
        Instant nextPollAt = clock.instant().plus(sourceAdapter.pollingInterval(claim.channel()));
        return messageRepository.ingestPoll(
            claim.channel().id(), claim.processingToken(), pollResult, nextPollAt
        );
    }
}
```

Add `lease_renewals_total{result=renewed|failed|lost}` through `ImportMetrics` without ID labels.
Write one structured heartbeat log per outcome with `operation=channel_poll_lease_renewal`, safe channel/token context,
and `result`; do not add those IDs to metrics.

- [x] **Step 7: Run heartbeat, processor, and lease tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=PollLeaseHeartbeatTest,ChannelProcessorTest,LeaseConcurrencyTest test
```

Expected: PASS; stale poll emits no success sample and expired/wrong tokens cannot renew.

- [x] **Step 8: Commit Task 4**

```bash
git add src/main/java/org/moera/importer/application src/main/java/org/moera/importer/persistence/ChannelRepository.java src/main/java/org/moera/importer/observability/ImportMetrics.java src/test/java/org/moera/importer/application src/test/java/org/moera/importer/persistence/LeaseConcurrencyTest.java
git commit -m "feat: renew active poll leases"
```

---

### Task 5: Add durable per-destination create slots

**Files:**
- Create: `src/main/resources/db/migration/V003__destination_create_rate.sql`
- Create: `src/main/java/org/moera/importer/config/CreateRateProperties.java`
- Create: `src/main/java/org/moera/importer/persistence/DestinationCreateSlot.java`
- Create: `src/test/java/org/moera/importer/persistence/DestinationCreateRateLimitTest.java`
- Create: `src/test/java/org/moera/importer/config/CreateRatePropertiesTest.java`
- Modify: `src/main/java/org/moera/importer/persistence/DestinationRepository.java`
- Modify: `src/main/java/org/moera/importer/persistence/PublicationRepository.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/org/moera/importer/persistence/SchemaMigrationTest.java`

**Interfaces:**
- Produces: `Duration CreateRateProperties.interval()` from positive `count=10` and `period=1m`, rounded upward.
- Produces sealed `DestinationCreateSlot` with `Acquired` and `Deferred(Instant retryAt)` records.
- Produces: `DestinationCreateSlot DestinationRepository.acquireCreateSlot(long destinationId, Duration interval)`.
- Produces: `boolean PublicationRepository.deferCreateRate(long publicationId, UUID token, Instant nextAttemptAt)`.

- [x] **Step 1: Write failing schema, property, and concurrency tests**

Extend `SchemaMigrationTest` to assert nullable `destinations.next_create_at` has PostgreSQL type
`timestamp with time zone`.

In `DestinationCreateRateLimitTest`, insert two destinations and concurrently call `acquireCreateSlot` for one:

```java
assertThat(List.of(first.get(), second.get()))
    .filteredOn(DestinationCreateSlot.Acquired.class::isInstance)
    .hasSize(1);
assertThat(List.of(first.get(), second.get()))
    .filteredOn(DestinationCreateSlot.Deferred.class::isInstance)
    .hasSize(1);
```

Assert different destinations both acquire immediately. Add a publication fixture with `attempts=3` and a matching
token; `deferCreateRate` must set due time, clear lease, preserve `last_error`, and reduce attempts to 2. A stale token
must change nothing. After the first acquisition, assert `destinations.next_create_at` is non-null through an
independent jOOQ read and that a later repository call in a fresh transaction returns `Deferred`, proving JVM-local
state is not the rate source of truth.

Add a focused property test proving 10/1m yields exactly six seconds, invalid count/period fail validation, and a period
not evenly divisible by count rounds upward.

- [x] **Step 2: Run focused tests and verify RED**

Run:

```bash
./mvnw -Dtest=SchemaMigrationTest,DestinationCreateRateLimitTest,CreateRatePropertiesTest test
```

Expected: failure because V003, the generated field, properties, slot type, and repository methods do not exist.

- [x] **Step 3: Add V003 and regenerate jOOQ**

Migration content is exactly the forward schema change:

```sql
ALTER TABLE destinations
    ADD COLUMN next_create_at timestamptz;
```

Run:

```bash
./mvnw generate-sources
```

Expected: generated `DESTINATIONS.NEXT_CREATE_AT` is available under `target/generated-sources/jooq`; generated files
remain uncommitted.

- [x] **Step 4: Implement typed rate configuration and slot result**

Bind `CreateRateProperties` at `moera.delivery.create-rate`, with copying accessors and `@PostConstruct` validation.
Implement `interval()` as `period.dividedBy(count)`, then add one nanosecond when
`interval.multipliedBy(count).compareTo(period) < 0`; this rounds upward without converting the whole duration to a
possibly overflowing scalar. Add YAML defaults:

```yaml
delivery:
  create-rate:
    count: ${MOERA_CREATE_RATE_COUNT:10}
    period: ${MOERA_CREATE_RATE_PERIOD:1m}
```

Define the sealed result exactly:

```java
public sealed interface DestinationCreateSlot {

    record Acquired() implements DestinationCreateSlot {
    }

    record Deferred(Instant retryAt) implements DestinationCreateSlot {
    }

}
```

- [x] **Step 5: Implement destination slot acquisition and publication deferral**

`DestinationRepository.acquireCreateSlot` is `@Transactional`: lock the exact destination row `FOR UPDATE`, read
PostgreSQL `currentOffsetDateTime()`, and compare `next_create_at`. When due, update it to database-now plus interval and
return `Acquired`; otherwise return `Deferred(nextCreateAt.toInstant())` without an update.

```java
@Transactional
public DestinationCreateSlot acquireCreateSlot(long destinationId, Duration interval) {
    DestinationsRecord destination = dslContext.selectFrom(DESTINATIONS)
        .where(DESTINATIONS.ID.eq(destinationId))
        .forUpdate()
        .fetchSingle();
    OffsetDateTime databaseNow = dslContext.select(currentOffsetDateTime()).fetchSingle().value1();
    OffsetDateTime next = destination.getNextCreateAt();
    if (next != null && next.isAfter(databaseNow)) {
        return new DestinationCreateSlot.Deferred(next.toInstant());
    }
    dslContext.update(DESTINATIONS)
        .set(DESTINATIONS.NEXT_CREATE_AT, databaseNow.plus(interval))
        .where(DESTINATIONS.ID.eq(destinationId))
        .execute();
    return new DestinationCreateSlot.Acquired();
}
```

`PublicationRepository.deferCreateRate` conditionally matches publication ID and token, sets `next_attempt_at`, clears
processing token/until, updates `updated_at`, and sets attempts to `max(attempts - 1, 0)`. It must not change
`last_error`, revision, or destination data.

```java
return dslContext.update(PUBLICATIONS)
    .set(PUBLICATIONS.ATTEMPTS,
        when(PUBLICATIONS.ATTEMPTS.gt(0), PUBLICATIONS.ATTEMPTS.minus(1)).otherwise(0))
    .set(PUBLICATIONS.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(nextAttemptAt))
    .setNull(PUBLICATIONS.PROCESSING_TOKEN)
    .setNull(PUBLICATIONS.PROCESSING_UNTIL)
    .set(PUBLICATIONS.UPDATED_AT, jooqMappings.offsetDateTime(clock.instant()))
    .where(PUBLICATIONS.ID.eq(publicationId))
    .and(PUBLICATIONS.PROCESSING_TOKEN.eq(token))
    .execute() == 1;
```

- [x] **Step 6: Run schema and rate persistence tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=SchemaMigrationTest,DestinationCreateRateLimitTest,CreateRatePropertiesTest test
```

Expected: PASS against production Flyway migrations on Testcontainers PostgreSQL.

- [x] **Step 7: Commit Task 5**

```bash
git add src/main/resources/db/migration/V003__destination_create_rate.sql src/main/resources/application.yml src/main/java/org/moera/importer/config/CreateRateProperties.java src/main/java/org/moera/importer/persistence src/test/java/org/moera/importer/persistence src/test/java/org/moera/importer/config/CreateRatePropertiesTest.java
git commit -m "feat: persist destination create rate"
```

---

### Task 6: Apply the create rate gate in publication delivery

**Files:**
- Modify: `src/main/java/org/moera/importer/application/PublicationProcessor.java`
- Modify: `src/main/java/org/moera/importer/observability/ImportMetrics.java`
- Modify: `src/test/java/org/moera/importer/application/PublicationProcessorTest.java`

**Interfaces:**
- Consumes: `DestinationRepository.acquireCreateSlot`, `PublicationRepository.deferCreateRate`, and
  `CreateRateProperties.interval()` from Task 5.
- Produces: structured publication result `rate_limited` and counter `publication_rate_limited_total`.

- [x] **Step 1: Write failing processor tests for the exact gate boundary**

Inject a recording `DestinationRepository` and `CreateRateProperties` into the existing processor fixture. Add:

```java
@Test
void defersFirstCreateWhenDestinationSlotIsNotDue() {
    publicationRepository.context = context(null, objectNode(), "Body", false, false);
    destinationRepository.slot = new DestinationCreateSlot.Deferred(NOW.plusSeconds(6));

    publicationProcessor.process(claim());

    assertThat(moeraService.operations).containsExactly("capabilities");
    assertThat(publicationRepository.deferredUntil).isEqualTo(NOW.plusSeconds(6));
    assertThat(publicationRepository.error).isNull();
    assertThat(meterRegistry.get("publication_rate_limited_total").counter().count()).isEqualTo(1);
}
```

Add distinct tests proving an acquired slot calls `create`, an update with an existing destination post never asks for
a slot, delete and empty-never-created paths never ask for a slot, a stale rate deferral logs `stale_claim`, and an
outcome-unknown create consumes its already acquired slot without any second acquisition.

- [x] **Step 2: Run processor tests and verify RED**

Run:

```bash
./mvnw -Dtest=PublicationProcessorTest test
```

Expected: compile/assertion failures because the processor has no create slot boundary or rate metric.

- [x] **Step 3: Implement the gate immediately before `createPost`**

After capabilities and payload construction, only in the non-empty `destinationPostId == null` branch:

```java
DestinationCreateSlot slot = destinationRepository.acquireCreateSlot(
    context.destination().id(), createRateProperties.interval()
);
if (slot instanceof DestinationCreateSlot.Deferred deferred) {
    boolean accepted = publicationRepository.deferCreateRate(
        claim.publicationId(), claim.processingToken(), deferred.retryAt()
    );
    if (accepted) {
        importMetrics.publicationRateLimited();
    }
    log(claim, accepted ? "rate_limited" : "stale_claim");
    return;
}
```

Then call the existing `createPost` path unchanged. Do not gate capability lookup, update, delete, story update, or
media. Add the one unlabelled Micrometer counter method to `ImportMetrics`.

- [x] **Step 4: Run publication processor and persistence tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=PublicationProcessorTest,DestinationCreateRateLimitTest,PublicationRevisionRaceTest test
```

Expected: PASS; only first non-empty creates touch destination rate state.

- [x] **Step 5: Commit Task 6**

```bash
git add src/main/java/org/moera/importer/application/PublicationProcessor.java src/main/java/org/moera/importer/observability/ImportMetrics.java src/test/java/org/moera/importer/application/PublicationProcessorTest.java
git commit -m "feat: rate limit Moera post creation"
```

---

### Task 7: Document operation, cross-check the pipeline, and run full verification

**Files:**
- Modify: `doc/operations.md`
- Modify: `doc/architecture/implementation-plan.md`
- Modify: `src/test/java/org/moera/importer/ImportPipelineTest.java`

**Interfaces:**
- Consumes: all Tasks 1–6.
- Produces: operator-visible configuration/default documentation and an end-to-end regression covering safe defaults.

- [x] **Step 1: Write the failing end-to-end assertions**

Extend `ImportPipelineTest` so its fake channel resolution receives `InitialImportMode.RECENT`, its default initial
poll fixture exposes no more than twenty source messages, and its first Moera create receives an acquired destination
slot. Add an assertion that an existing-post update completes without a second slot acquisition.

- [x] **Step 2: Run the pipeline test and verify RED if wiring is incomplete**

Run:

```bash
./mvnw -Dtest=ImportPipelineTest test
```

Expected before final wiring corrections: failure at the first unupdated fake, missing property injection, or missing
rate-slot fixture; do not weaken assertions to make it pass.

- [x] **Step 3: Make only the minimal wiring corrections required by the pipeline test**

Update fake adapter signatures, inject deterministic `CreateRateProperties`, and use an acquired recording destination
slot. Do not add production behavior beyond Tasks 1–6.

```java
ReflectionTestUtils.setField(publicationProcessor, "destinationRepository", destinationRepository);
ReflectionTestUtils.setField(publicationProcessor, "createRateProperties", createRateProperties);
destinationRepository.slot = new DestinationCreateSlot.Acquired();
```

- [x] **Step 4: Update operational and implementation documentation**

Document exact defaults and environment overrides:

```text
TELEGRAM_INITIAL_HISTORY_COUNT=20
MOERA_CREATE_RATE_COUNT=10
MOERA_CREATE_RATE_PERIOD=1m
channel concurrency=2
publication-builder concurrency=2
publication-media concurrency=4
publication concurrency=4
```

Document `channel add --full-history`, the fact that old backlog is retained, and that corrected startup delivers it at
the configured destination rate. Append Task 15 to `doc/architecture/implementation-plan.md` with a link to this plan
and mark its checklist complete only as the corresponding implementation tasks finish.

- [x] **Step 5: Run focused static checks**

Run:

```bash
python3 -m unittest scripts/test_moera_import_admin.py
python3 scripts/moera-import-admin --help
git diff --check
```

Expected: Python tests pass, help renders, and `git diff --check` prints nothing.

- [x] **Step 6: Run the complete production verification**

Run:

```bash
./mvnw clean verify
```

Expected: every JUnit 5 and Testcontainers test passes; real Telegram and Moera smoke tests remain excluded.

- [x] **Step 7: Review the implementation against every spec decision**

Check each section of `doc/architecture/import-flow-control-design.md` against code and tests. Confirm:

```text
no scheduler claims above available capacity
poll heartbeat survives until ingest returns
stale ingest is never logged as success
only createPost is rate limited
destination rate state survives restart in PostgreSQL
default initial scan is twenty unique Telegram messages
full history requires explicit API/CLI opt-in
no existing backlog is deleted
no high-cardinality metric label was introduced
```

- [x] **Step 8: Commit Task 7**

```bash
git add doc scripts/test_moera_import_admin.py src/test/java/org/moera/importer/ImportPipelineTest.java
git commit -m "docs: document bounded import operation"
```
