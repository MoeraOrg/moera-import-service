# Moera Import Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first production-capable Moera import service that mirrors Telegram channels into Moera according
to the architecture specification, while preserving extension points for future source adapters.

**Architecture:** PostgreSQL stores all durable channel/message/publication state and doubles as the durable work
queue. Source-specific behavior is behind `SourceAdapter`; Telegram is implemented through TDLight. Moera delivery is
concrete through `MoeraService`. External work uses short lease claims plus virtual threads; pure database grouping
uses short row-locking transactions.

**Tech Stack:** Java 25, Spring Boot/Spring Framework, Maven, PostgreSQL, Flyway, jOOQ, TDLight, Moera Java library,
JUnit 5, Testcontainers, Micrometer/Actuator.

**Spec:** `doc/architecture/import-service.md`

## Global Constraints

- Java language level is 25.
- PostgreSQL is the only production database.
- Flyway SQL migrations are the only schema source of truth.
- jOOQ is generated from the Flyway-defined schema and stays inside persistence code.
- Do not introduce Hibernate/JPA, repository interfaces, Reactor, a generic jobs table, or a generic destination
  abstraction.
- Database table names are plural; `media` remains `media`.
- Telegram is the only initial `source_type` value.
- Moera is the only destination; always use `downsize=true` for media upload.
- Keep asynchronously compressed Moera media `PENDING` until the final compressed ID/hash is available; use
  synchronous `createPosting`/`updatePosting`, not remote-posting APIs.
- Only the latest `Message` revision is stored; `revision` starts at 0.
- Publication membership is immutable; no reconciliation/regrouping is implemented.
- Live source events never advance the Telegram waterline.
- External calls never run inside open PostgreSQL transactions.
- Repository integration tests use real PostgreSQL via Testcontainers, never H2.
- Real Telegram/Moera tests are opt-in smoke tests, not default CI tests.
- Do not log credentials, invite links, verification codes, 2FA passwords, or message bodies.

---

## File map

The initial implementation uses Maven coordinates `org.moera:moera-import` and base package `org.moera.importer`.

Key production packages:

```text
src/main/java/org/moera/importer/
  MoeraImportApplication.java
  config/
  model/
  source/
  source/telegram/
  moera/
  persistence/
  application/
  scheduling/
  staging/
```

Key tests:

```text
src/test/java/org/moera/importer/
  model/
  persistence/
  application/
  source/telegram/
  moera/
  staging/
```

---

### Task 1: Bootstrap the build, schema, jOOQ generation, and PostgreSQL test harness

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/org/moera/importer/MoeraImportApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/db/migration/V001__initial_schema.sql`
- Create: `src/test/java/org/moera/importer/persistence/SchemaMigrationTest.java`
- Create: `src/test/resources/application-test.yml`

**Interfaces:**
- Produces: Maven build for Java 25; Flyway-managed schema; generated jOOQ classes under
  `target/generated-sources/jooq`; reusable Testcontainers PostgreSQL integration-test configuration.
- Consumes: `docs/architecture/import-service.md` and the agreed `V001__initial_schema.sql`.

- [ ] **Step 1: Pin current compatible dependency versions**

Verify current stable releases from official project/Maven metadata at implementation time. Use Spring Boot dependency
management for Spring, Flyway, PostgreSQL driver, Micrometer, and Testcontainers where managed. Pin TDLight, Moera Java
library, and jOOQ codegen additions explicitly only where the Spring BOM does not manage the required artifact.

Record the chosen versions directly in `pom.xml`; do not leave version placeholders.

- [ ] **Step 2: Write the failing schema integration test**

Create `SchemaMigrationTest` using Testcontainers PostgreSQL and Flyway. The test must assert that migration succeeds
and that at least these relations exist:

```java
assertThat(tableNames).contains(
    "channels",
    "messages",
    "media",
    "destinations",
    "mirrors",
    "publications",
    "publication_messages",
    "publication_media"
);
```

Also query `pg_type` and assert that `source_type` contains only `TELEGRAM`.

- [ ] **Step 3: Run the test and verify it fails**

Run:

```bash
./mvnw -Dtest=SchemaMigrationTest test
```

Expected result: failure because the Maven project/schema/test harness does not yet exist or the migration has not been
installed.

- [ ] **Step 4: Create the Maven project and application entry point**

Create a Spring Boot Maven project with Java 25 and dependencies for:

```text
spring-boot-starter
spring-boot-starter-actuator
spring-boot-starter-jooq
flyway-core
flyway-database-postgresql
postgresql
jackson-databind
micrometer-registry-prometheus
junit-jupiter
testcontainers-postgresql
spring-boot-test
```

Add Maven Wrapper files if the repository does not already contain them. The application entry point is:

```java
package org.moera.importer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MoeraImportApplication {
    public static void main(String[] args) {
        SpringApplication.run(MoeraImportApplication.class, args);
    }
}
```

- [ ] **Step 5: Install the agreed Flyway migration**

Copy the reviewed `V001__initial_schema.sql` from the design artifacts unchanged except for fixes that are explicitly
approved against the spec. Do not generate schema from Java.

- [ ] **Step 6: Configure jOOQ code generation from Flyway SQL**

Configure jOOQ Maven code generation to read the migration SQL as the schema source, using jOOQ's DDL metadata support
with PostgreSQL dialect semantics. Generate classes into package:

```text
org.moera.importer.persistence.jooq
```

Generated sources must be under `target/generated-sources/jooq` and must not be committed unless the repository later
adopts that policy explicitly.

- [ ] **Step 7: Run schema and code-generation verification**

Run:

```bash
./mvnw clean generate-sources test
```

Expected result: jOOQ generation succeeds and `SchemaMigrationTest` passes against Testcontainers PostgreSQL.

- [ ] **Step 8: Commit**

```bash
git add pom.xml .mvn mvnw mvnw.cmd src/main src/test
git commit -m "build: bootstrap import service persistence"
```

---

### Task 2: Add universal model, source contracts, hashing, and retry policy

**Files:**
- Create: `src/main/java/org/moera/importer/model/BodyFormat.java`
- Create: `src/main/java/org/moera/importer/model/SourceType.java`
- Create: `src/main/java/org/moera/importer/model/AccessState.java`
- Create: `src/main/java/org/moera/importer/model/SubscriptionState.java`
- Create: `src/main/java/org/moera/importer/model/Message.java`
- Create: `src/main/java/org/moera/importer/model/Media.java`
- Create: `src/main/java/org/moera/importer/model/Destination.java`
- Create: `src/main/java/org/moera/importer/model/Mirror.java`
- Create: `src/main/java/org/moera/importer/source/SourceAdapter.java`
- Create: `src/main/java/org/moera/importer/source/SourceAdapterRegistry.java`
- Create: `src/main/java/org/moera/importer/source/ChannelSnapshot.java`
- Create: `src/main/java/org/moera/importer/source/MediaSnapshot.java`
- Create: `src/main/java/org/moera/importer/source/SourceMessage.java`
- Create: `src/main/java/org/moera/importer/source/SourceMedia.java`
- Create: `src/main/java/org/moera/importer/source/PollResult.java`
- Create: `src/main/java/org/moera/importer/source/AccessResult.java`
- Create: `src/main/java/org/moera/importer/source/SubscriptionResult.java`
- Create: `src/main/java/org/moera/importer/source/ChannelResolution.java`
- Create: `src/main/java/org/moera/importer/source/GroupingRelation.java`
- Create: `src/main/java/org/moera/importer/source/SourceEvent.java`
- Create: `src/main/java/org/moera/importer/source/SourceEventSink.java`
- Create: `src/main/java/org/moera/importer/source/SourceTemporaryException.java`
- Create: `src/main/java/org/moera/importer/source/SourcePermanentException.java`
- Create: `src/main/java/org/moera/importer/application/RetryPolicy.java`
- Create: `src/main/java/org/moera/importer/application/Sha256.java`
- Test: `src/test/java/org/moera/importer/application/RetryPolicyTest.java`
- Test: `src/test/java/org/moera/importer/application/Sha256Test.java`

**Interfaces:**
- Produces: `SourceAdapter` contract and immutable DTOs used by later Telegram/application tasks.
- Produces exact source operations:

```java
SourceType type();
ChannelResolution resolveChannel(URI sourceUri);
Duration groupingThreshold();
Duration settlingDelay();
Duration pollingInterval(ChannelSnapshot channel);
GroupingRelation groupingRelation(SourceMessage previous, SourceMessage next);
AccessResult refreshAccess(ChannelSnapshot channel);
SubscriptionResult refreshSubscription(ChannelSnapshot channel);
PollResult poll(ChannelSnapshot channel);
void downloadMedia(ChannelSnapshot channel, MediaSnapshot media, Path destination);
void start(SourceEventSink sink);
void stop();
```

- [ ] **Step 1: Write failing RetryPolicy tests**

Test exponential delay, maximum cap, deterministic no-jitter mode for tests, and external `retryAfter` precedence.
Include assertions equivalent to:

```java
assertThat(policy.delay(1, null)).isEqualTo(Duration.ofSeconds(5));
assertThat(policy.delay(2, null)).isEqualTo(Duration.ofSeconds(10));
assertThat(policy.delay(20, null)).isEqualTo(Duration.ofMinutes(15));
assertThat(policy.delay(2, Duration.ofMinutes(3))).isEqualTo(Duration.ofMinutes(3));
```

- [ ] **Step 2: Run RetryPolicy tests and verify failure**

```bash
./mvnw -Dtest=RetryPolicyTest test
```

Expected: compile/test failure because `RetryPolicy` does not exist.

- [ ] **Step 3: Implement RetryPolicy minimally**

Use constructor parameters `Duration baseDelay`, `Duration maxDelay`, and `double jitterFraction`. Provide a
package-visible deterministic jitter supplier or constructor overload so tests do not depend on randomness.

- [ ] **Step 4: Write failing SHA-256 tests**

Require `Sha256.digest(byte[])` to always return 32 bytes and `Sha256.hex(byte[])` to return 64 lower-case hex
characters.

- [ ] **Step 5: Implement SHA-256 utility**

Use JDK `MessageDigest` and `HexFormat`; do not add a crypto dependency.

- [ ] **Step 6: Add model/source records and sealed events**

Use Java records where values are immutable. Define `SourceEvent` as a sealed interface with records:

```java
MessageChanged(SourceType sourceType, String channelSourceId, SourceMessage message)
MessageDeleted(SourceType sourceType, String channelSourceId, String messageSourceId)
AccessLost(SourceType sourceType, String channelSourceId, JsonNode data)
SubscriptionLost(SourceType sourceType, String channelSourceId, JsonNode data)
```

`ChannelResolution.sourceId()` and `AccessResult.resolvedSourceId()` are nullable because Telegram private invites may
not reveal identity until access is granted.

- [ ] **Step 7: Add SourceAdapterRegistry**

Construct it from `List<SourceAdapter>` and reject duplicate `SourceType` registrations during startup.
`get(SourceType)` throws a clear configuration exception for an unregistered type.

- [ ] **Step 8: Run unit tests**

```bash
./mvnw -Dtest=RetryPolicyTest,Sha256Test test
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add \
  src/main/java/org/moera/importer/model \
  src/main/java/org/moera/importer/source \
  src/main/java/org/moera/importer/application \
  src/test/java/org/moera/importer/application
git commit -m "feat: define source and domain contracts"
```

---

### Task 3: Implement channel/message/media persistence and atomic ingest

**Files:**
- Create: `src/main/java/org/moera/importer/persistence/ChannelRepository.java`
- Create: `src/main/java/org/moera/importer/persistence/MessageRepository.java`
- Create: `src/main/java/org/moera/importer/persistence/JooqMappings.java`
- Create: `src/main/java/org/moera/importer/persistence/IngestResult.java`
- Create: `src/test/java/org/moera/importer/persistence/MessageRepositoryTest.java`
- Create: `src/test/java/org/moera/importer/persistence/PollIngestTest.java`

**Interfaces:**
- Produces:

```java
Optional<ChannelSnapshot> ChannelRepository.find(long id);
Optional<ChannelSnapshot> ChannelRepository.find(SourceType sourceType, String sourceId);
IngestResult MessageRepository.ingest(long channelId, SourceMessage message);
boolean MessageRepository.markDeleted(long channelId, String sourceMessageId);
boolean MessageRepository.ingestPoll(long channelId, UUID processingToken, PollResult result, Instant nextPollAt);
```

- `IngestResult` fields: `long messageId`, `int revision`, `boolean created`, `boolean changed`.

- [ ] **Step 1: Write failing new-message ingest test**

Insert a channel fixture, ingest one `SourceMessage`, and assert:

```text
messages.revision = 0
messages.body/source hash persisted
media rows persisted in position order
IngestResult.created = true
IngestResult.changed = true
```

- [ ] **Step 2: Run the test and verify failure**

```bash
./mvnw -Dtest=MessageRepositoryTest#ingestsNewMessageAtRevisionZero test
```

Expected: FAIL because repository methods do not exist.

- [ ] **Step 3: Implement insert path with jOOQ**

Keep all generated table references inside `MessageRepository`. Map database enum/string/json values through
`JooqMappings` into normal records.

- [ ] **Step 4: Add idempotent re-ingest test**

Ingest the identical `SourceMessage` twice. Assert there is still one message row, unchanged media rows, and revision
remains 0 with `changed=false` on the second call.

- [ ] **Step 5: Add body/media/deletion revision tests**

Cover separately:

```text
body hash change         -> revision 1
formatting-only hash     -> revision increments
media source_hash change -> revision increments
media addition/removal   -> revision increments
deleted false -> true    -> revision increments
same deleted state       -> no increment
```

- [ ] **Step 6: Implement update/media synchronization path**

Within one transaction compare normalized source state, update `messages`, replace/update only changed `media`, and
increment `revision` once per ingest regardless of how many individual fields changed.

- [ ] **Step 7: Write failing atomic poll test**

Create a channel with a valid `processing_token`, ingest a `PollResult`, and assert in one committed result:

```text
messages saved
waterline advanced
last_poll_at set
next_poll_at set to supplied value
poll_attempts reset to 0
poll_last_error cleared
lease columns cleared
```

Use a stale token in a second test and assert that neither messages nor waterline change.

- [ ] **Step 8: Implement `ingestPoll` as one transaction**

Verify token first, apply all message ingest changes, then update waterline/poll scheduling and clear lease. A stale
token returns `false` without persisting any part of the poll result.

- [ ] **Step 9: Run repository tests**

```bash
./mvnw -Dtest=MessageRepositoryTest,PollIngestTest test
```

Expected: PASS against Testcontainers PostgreSQL.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/org/moera/importer/persistence src/test/java/org/moera/importer/persistence
git commit -m "feat: persist channels messages and media"
```

---

### Task 4: Implement mirrors, publication grouping, and propagation of message/media changes

**Files:**
- Create: `src/main/java/org/moera/importer/persistence/DestinationRepository.java`
- Create: `src/main/java/org/moera/importer/persistence/MirrorRepository.java`
- Create: `src/main/java/org/moera/importer/persistence/PublicationRepository.java`
- Create: `src/main/java/org/moera/importer/persistence/PublicationMediaRepository.java`
- Create: `src/main/java/org/moera/importer/application/PublicationBuilder.java`
- Create: `src/test/java/org/moera/importer/application/PublicationBuilderTest.java`
- Modify: `src/main/java/org/moera/importer/persistence/MessageRepository.java`
- Modify: `src/test/java/org/moera/importer/persistence/MessageRepositoryTest.java`

**Interfaces:**
- Produces:

```java
List<Long> MirrorRepository.findWithUnassignedMessages(int limit);
Optional<Mirror> MirrorRepository.lockForBuild(long mirrorId);
List<Message> MessageRepository.findUnassignedForMirror(long mirrorId);
void PublicationRepository.create(long mirrorId, List<Message> orderedMessages);
void PublicationBuilder.build(long mirrorId);
```

- [ ] **Step 1: Write failing grouping tests with a fake SourceAdapter**

Cover:

```text
10s + 20s gaps with 30s threshold -> one publication
10s + 40s gap                  -> split
MUST_GROUP                      -> join beyond threshold
MUST_SEPARATE                   -> split inside threshold
final live group not settled    -> no publication yet
historical final group settled  -> publication created
```

Use a fixed `Clock` in `PublicationBuilder` so timing tests are deterministic.

- [ ] **Step 2: Run grouping tests and verify failure**

```bash
./mvnw -Dtest=PublicationBuilderTest test
```

Expected: FAIL because builder/repository operations do not exist.

- [ ] **Step 3: Implement mirror lock and unassigned-message query**

`PublicationBuilder.build(mirrorId)` is `@Transactional`. Lock the mirror row using `FOR UPDATE SKIP LOCKED`, load
messages in `(published_at, id)` order, and return without work when the mirror is already locked by another builder.

- [ ] **Step 4: Implement grouping and atomic publication creation**

Create `publications` at revision 0 with `published_revision=NULL` and `next_attempt_at=now()`. Insert fixed-order
`publication_messages`. Insert one `publication_media` row for each current source media item with `status=PENDING` and
`next_attempt_at=now()`.

- [ ] **Step 5: Write failing propagation tests**

Create a publication containing a message, then ingest a changed message and assert:

```text
message.revision increments
publication.revision increments once
publication attempts/error reset
publication.next_attempt_at = now
changed/added media delivery state is PENDING
removed media delivery state disappears through FK/source synchronization
active media lease is invalidated for changed source_hash
```

- [ ] **Step 6: Implement propagation inside MessageRepository transaction**

Use `publication_messages(message_id)` and `publication_media(media_id)` reverse lookups. Keep membership unchanged;
only mark existing publications dirty and synchronize current media delivery rows.

- [ ] **Step 7: Run grouping and propagation tests**

```bash
./mvnw -Dtest=PublicationBuilderTest,MessageRepositoryTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add \
  src/main/java/org/moera/importer/persistence \
  src/main/java/org/moera/importer/application/PublicationBuilder.java \
  src/test/java/org/moera/importer
git commit -m "feat: build and revise publications"
```

---

### Task 5: Implement leasing, channel workflow, and scheduler concurrency

**Files:**
- Create: `src/main/java/org/moera/importer/persistence/ChannelClaim.java`
- Create: `src/main/java/org/moera/importer/persistence/PublicationClaim.java`
- Create: `src/main/java/org/moera/importer/persistence/PublicationMediaClaim.java`
- Create: `src/main/java/org/moera/importer/application/ChannelProcessor.java`
- Create: `src/main/java/org/moera/importer/application/SourceEventProcessor.java`
- Create: `src/main/java/org/moera/importer/scheduling/ChannelScheduler.java`
- Create: `src/main/java/org/moera/importer/scheduling/PublicationBuilderScheduler.java`
- Create: `src/main/java/org/moera/importer/config/WorkerProperties.java`
- Create: `src/test/java/org/moera/importer/persistence/LeaseConcurrencyTest.java`
- Create: `src/test/java/org/moera/importer/application/ChannelProcessorTest.java`
- Modify: repository classes from Tasks 3-4

**Interfaces:**
- Produces:

```java
List<ChannelClaim> ChannelRepository.claimAccess(int limit, Duration lease);
List<ChannelClaim> ChannelRepository.claimSubscriptions(int limit, Duration lease);
List<ChannelClaim> ChannelRepository.claimPolls(int limit, Duration lease);
boolean ChannelRepository.completeAccess(long channelId, UUID token, AccessResult result);
boolean ChannelRepository.completeSubscription(long channelId, UUID token, SubscriptionResult result);
boolean ChannelRepository.failAccess(long channelId, UUID token, Instant nextAttemptAt, String error);
boolean ChannelRepository.failSubscription(long channelId, UUID token, Instant nextAttemptAt, String error);
boolean ChannelRepository.failPoll(long channelId, UUID token, Instant nextAttemptAt, String error);
```

- [ ] **Step 1: Write failing two-worker claim test**

Using two independent transactions/threads, insert one due channel and concurrently call `claimPolls(1, lease)`. Assert
exactly one caller receives the row.

- [ ] **Step 2: Run concurrency test and verify failure**

```bash
./mvnw -Dtest=LeaseConcurrencyTest#onlyOneWorkerClaimsChannel test
```

Expected: FAIL until claim SQL exists.

- [ ] **Step 3: Implement claims with `FOR UPDATE SKIP LOCKED`**

Claims must assign a random UUID token, set `processing_operation`, set `processing_until`, and for work with attempt
counters increment attempts at claim time. Treat `processing_until IS NULL OR processing_until < now()` as available.

- [ ] **Step 4: Add expired-lease and stale-token tests**

Assert an expired lease can be reclaimed with a new token, and completion with the old token updates zero rows and
cannot overwrite newer state.

- [ ] **Step 5: Write ChannelProcessor tests with FakeSourceAdapter**

Cover successful access, subscription, poll, temporary exception scheduling, permanent exception stop, and stale
completion. A successful poll must call `MessageRepository.ingestPoll` rather than advance waterline separately.

- [ ] **Step 6: Implement ChannelProcessor and SourceEventProcessor**

Use `SourceAdapterRegistry`. `SourceEventProcessor` immediately maps live events to
`MessageRepository.ingest/markDeleted` or channel state updates. It does not dispatch Telegram events onto
unconstrained parallel threads.

- [ ] **Step 7: Implement thin schedulers with virtual threads**

Create one Spring-managed virtual-thread executor. `ChannelScheduler` claims access first, then subscription, then poll
up to batch size. `PublicationBuilderScheduler` only finds mirror IDs and invokes builder work.

- [ ] **Step 8: Run concurrency/workflow tests**

```bash
./mvnw -Dtest=LeaseConcurrencyTest,ChannelProcessorTest test
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add \
  src/main/java/org/moera/importer/application \
  src/main/java/org/moera/importer/scheduling \
  src/main/java/org/moera/importer/persistence \
  src/main/java/org/moera/importer/config \
  src/test/java/org/moera/importer
git commit -m "feat: add leased channel processing"
```

---

### Task 6: Extend the universal message model and persistence for pinning and link previews

Tasks 1–5 are already implemented against `V001__initial_schema.sql`; do not rewrite that migration or retrofit these
requirements into completed tasks. Add the new schema and repository behavior forward-only in this task.

**Files:**
- Create: `src/main/resources/db/migration/V002__message_pinning_and_link_previews.sql`
- Create: `src/main/java/org/moera/importer/source/SourceLinkPreview.java`
- Create: `src/main/java/org/moera/importer/persistence/MessageLinkPreview.java`
- Create: `src/test/java/org/moera/importer/persistence/MessageLinkPreviewRepositoryTest.java`
- Modify: `src/main/java/org/moera/importer/source/SourceMessage.java`
- Modify: `src/main/java/org/moera/importer/model/Message.java`
- Modify: `src/main/java/org/moera/importer/persistence/MessageRepository.java`
- Modify: `src/main/java/org/moera/importer/persistence/JooqMappings.java`
- Modify: `src/main/java/org/moera/importer/persistence/PublicationRepository.java`
- Modify: `src/main/java/org/moera/importer/persistence/PublicationMediaRepository.java`
- Modify: existing source/repository fixtures that construct `SourceMessage`
- Modify: `src/test/java/org/moera/importer/persistence/SchemaMigrationTest.java`
- Modify: `src/test/java/org/moera/importer/persistence/MessageRepositoryTest.java`
- Modify: `src/test/java/org/moera/importer/persistence/PollIngestTest.java`
- Modify: `src/test/java/org/moera/importer/application/PublicationBuilderTest.java`

**Interfaces:**
- Produces:

```java
public record SourceLinkPreview(
    String siteName,
    String url,
    String title,
    String description,
    Instant publishedAt,
    String imageSourceMediaId
) {}

public record MessageLinkPreview(
    long messageId,
    int position,
    String siteName,
    String url,
    String title,
    String description,
    Instant publishedAt,
    Long imageMediaId
) {}

public record SourceMessage(
    String sourceId,
    boolean deleted,
    boolean pinned,
    Instant publishedAt,
    String body,
    BodyFormat bodyFormat,
    byte[] sourceBodyHash,
    JsonNode sourceData,
    List<SourceMedia> media,
    List<SourceLinkPreview> linkPreviews
) {}

List<MessageLinkPreview> MessageRepository.findLinkPreviews(long messageId);
```

`SourceLinkPreview.url` is required. `imageSourceMediaId` refers to `SourceMedia.sourceId` within the same message;
persistence resolves it to the matching `media.id`, and it is nullable when a preview has no selected image.

- [ ] **Step 1: Write failing migration and persistence tests**

Extend `SchemaMigrationTest` to require `messages.pinned boolean NOT NULL DEFAULT false` and the
`message_link_previews` table with an owning message, zero-based position, non-null URL, nullable descriptive fields,
and nullable `image_media_id REFERENCES media(id)`. In repository tests, ingest a pinned message with two ordered
previews, one of which refers to an ordinary media item; assert all values and the resolved media foreign key can be
read back.

- [ ] **Step 2: Run the focused tests and verify failure**

```bash
./mvnw -Dtest=SchemaMigrationTest,MessageLinkPreviewRepositoryTest,MessageRepositoryTest test
```

Expected: FAIL because `V002`, the universal preview type, and repository synchronization do not exist.

- [ ] **Step 3: Add the forward-only Flyway migration and regenerate jOOQ**

Create `V002__message_pinning_and_link_previews.sql`. Use direct PK/FK/UNIQUE/NOT NULL/simple position checks only;
do not encode cross-message image ownership as a complex database constraint. Run:

```bash
./mvnw generate-sources
```

Expected: generated `MESSAGES.PINNED` and `MESSAGE_LINK_PREVIEWS` jOOQ artifacts are available to compilation.

- [ ] **Step 4: Extend immutable universal values**

Add `SourceLinkPreview`, `SourceMessage.pinned`, and `SourceMessage.linkPreviews`. Defensively copy the preview list and
keep nullable preview fields nullable rather than using empty JSON. Extend the persistent `Message` projection with
`pinned`; keep link previews as ordered child records loaded for publication snapshots.

- [ ] **Step 5: Implement atomic preview persistence and revision detection**

Inside the existing short ingest transaction, insert/update `messages.pinned`, validate preview image references,
remove old preview rows before deleting/replacing referenced media, synchronize media, then insert previews in source
order with newly resolved media IDs. A change to pin state, effective preview fields,
preview order, or selected image reference increments the message/publication revision. Metadata refreshes that do not
change destination-visible state remain revision-neutral. Deleted messages retain their stored preview rows, but
publication payload construction ignores all content of deleted members.

- [ ] **Step 6: Add replacement, removal, effective-media, and poll-atomicity tests**

Prove that re-ingest can reorder previews, replace their image reference, remove all previews, and change pin state;
that missing/non-owned `imageSourceMediaId` is rejected before commit; and that a poll batch persists previews and its
waterline atomically. At publication creation and after member changes, derive effective media as all non-preview media
plus images referenced by the first preview for each URL in fixed member/source order. Assert that later duplicate
preview images are absent from `publication_media`, and that a later image is inserted and armed when removal of the
earlier preview makes it effective. Recalculate this set for every affected immutable-membership publication, deleting
obsolete delivery rows and preserving unchanged upload state. Also update all existing `SourceMessage` fixtures
without changing their prior semantics.

- [ ] **Step 7: Run the complete Task 6 test set**

```bash
./mvnw \
  -Dtest=SchemaMigrationTest,MessageLinkPreviewRepositoryTest,MessageRepositoryTest,PollIngestTest,\
PublicationBuilderTest,SourceMessageTest \
  test
```

Expected: PASS against migrated Testcontainers PostgreSQL.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration src/main/java/org/moera/importer src/test/java/org/moera/importer
git commit -m "feat: persist message pinning and link previews"
```

---

### Task 7: Implement explicit Telegram mapping, formatting, skips, and canonical hashes

**Files:**
- Create: `src/main/java/org/moera/importer/source/telegram/TelegramMappingResult.java`
- Create: `src/main/java/org/moera/importer/source/telegram/TelegramSkipReason.java`
- Create: `src/main/java/org/moera/importer/source/telegram/TelegramMessageMapper.java`
- Create: `src/main/java/org/moera/importer/source/telegram/TelegramHtmlFormatter.java`
- Create: `src/main/java/org/moera/importer/source/telegram/TelegramCanonicalHash.java`
- Create: `src/test/java/org/moera/importer/source/telegram/TelegramMessageMapperTest.java`
- Create: `src/test/java/org/moera/importer/source/telegram/TelegramHtmlFormatterTest.java`
- Create: `src/test/java/org/moera/importer/source/telegram/TelegramCanonicalHashTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Produces:

```java
sealed interface TelegramMappingResult {

    record Mapped(SourceMessage message) implements TelegramMappingResult {}
    record Skipped(TelegramSkipReason reason) implements TelegramMappingResult {}

}

enum TelegramSkipReason {
    UNSUPPORTED_CONTENT,
    SERVICE_MESSAGE,
    INVALID_CONTENT
}

TelegramMappingResult TelegramMessageMapper.map(TdApi.Message message);
String TelegramHtmlFormatter.format(TdApi.FormattedText text);
byte[] TelegramCanonicalHash.body(TdApi.Message message);
byte[] TelegramCanonicalHash.media(TdApi.File file, JsonNode normalizedSourceData);
```

`TelegramSkipReason` never embeds source IDs or free-form source text.

- [ ] **Step 1: Add TDLight and write failing paragraph/entity formatter tests**

Cover HTML escaping; one newline as `<br>`; two or more newlines as a `<p>` boundary without empty edge paragraphs;
TDLib UTF-16 offsets including surrogate pairs; valid nesting of overlapping ranges; and these exact mappings:

```text
Bold -> <b>                         Italic -> <i>
Underline -> <u>                    Strikethrough -> <s>
Code -> <code>                      Pre / PreCode -> <pre>
BlockQuote / ExpandableBlockQuote -> <blockquote>
```

Also test inline `<mr-spoiler>` versus `<mr-spoiler-block>`, custom emoji visible text, safe URL/TextUrl/email/phone
links, `@username` links, plain ID mentions, and plain hashtag/cashtag/bot-command/bank-card/timestamp/date-time text.
Unknown entities preserve visible text while remaining distinguishable to canonical hashing.

- [ ] **Step 2: Run formatter tests and verify failure**

```bash
./mvnw -Dtest=TelegramHtmlFormatterTest test
```

Expected: FAIL because the formatter does not exist.

- [ ] **Step 3: Implement the sanitized HTML formatter**

Escape raw text and attributes, allow only the exact tags and URI schemes above, ignore `PreCode` language, and never
pass arbitrary Telegram/source HTML through. Keep block construction separate from inline entity rendering so
paragraphs are represented by `<p>`, never `<br><br>`.

- [ ] **Step 4: Write failing mapper and skip-result tests**

Cover the explicit supported allowlist: `MessageText`; photo, animation, audio, document, video, video note, voice note;
`MessageSticker`; `MessageAnimatedEmoji`; `MessagePoll`; `MessageChecklist`; location/live location; venue; contact; and
expired photo/video/video-note/voice-note. Verify ordinary file rules: largest photo, primary video, still part of live
photo, main file only, caption as body, no thumbnail/cover attachment except a selected preview image, and no technical
metadata added to body.

For structured content assert:

```text
poll          -> question paragraph + ordered options
quiz          -> poll + <mr-spoiler-block title="Right answer"> when answers are known
checklist     -> title + ordered ☐/☑ tasks
location      -> OpenStreetMap coordinate link
venue         -> name + address + OpenStreetMap link
contact       -> name + tel: link
sticker       -> emoji + ordinary main-file media
expired media -> [Media is no longer available] and no media
```

Poll rendering includes only question and option text. Ignore vote counts, current choices/results, open/closed state,
description, explanation, and `PollMedia`. Test an unrevealed quiz without a spoiler, a revealed quiz, and multiple
correct answers rendered as a `<ul>` inside the titled spoiler. A later answer reveal must change the canonical hash.
Checklist completion changes the hash; completer/time/permission data does not.
Location accuracy/heading/duration, venue provider identity/type, contact Telegram user ID/vCard, and animated-emoji
animation are ignored. Test that a normal media message changing to its expired counterpart removes the previously
effective publication media.

Test explicit skipping of dice/stake-dice, games, invoices, paid media, stories, giveaways/gifts/currencies/payments,
`MessageRichMessage`, `MessageUnsupported`, every known administrative/service family, and an unknown future
`MessageContent`. `MessagePinMessage` is skipped; `TdApi.Message.isPinned` populates `SourceMessage.pinned`.

- [ ] **Step 5: Add forwarding, metadata, and link-preview mapper tests**

Test source attribution at the start of every forwarded body as
`<p><a href="source-url">Source name</a></p>`, exact public channel post links when possible, name-only fallback, and
omission when neither value exists. Test author signature as `<p><i>— Author</i></p>`, safe URL buttons after content,
ignored Telegram-only buttons, and the final fact-check block
`<hr><p><b>Fact check</b></p><blockquote>formatted text</blockquote>`. Also cover ignored replies, counters, reactions,
effects, timers, `canBeSaved`, restrictions, import/bot, and presentation metadata. Test all Moera preview fields,
image selection priority (largest main photo, otherwise thumbnail/cover), preview image as ordinary media,
missing-image fallback, and
ignored `linkPreviewOptions` and full preview video/audio/document content.

- [ ] **Step 6: Write canonical hash tests**

Assert deterministic SHA-256 over original text, ordered entities plus semantic data, forward attribution, author
signature, supported buttons, fact-check, poll/checklist state, and link-preview metadata. Relevant semantic
changes must change the hash; every explicitly ignored source field must not. Hash source values, not generated HTML.
`CustomEmoji` ID remains hash-relevant. Preview file identity belongs to the selected media `source_hash`; preview
metadata and selected image reference belong to `source_body_hash`.

- [ ] **Step 7: Implement the explicit mapper and canonical serializers**

Use decimal `message.id`, `message.date`, HTML body format, and universal media/preview values without downloading.
Return `Skipped`, never `null`, for intentional skips and invalid known content. Do not catch unexpected programming
failures as skips. Ignore `hasSpoiler`, `isSecret`, caption position, and other fields declared irrelevant by the
architecture. Store a stable normalized forward-attribution identity in Telegram `source_data` so grouped body
composition can compare adjacent messages without parsing generated HTML. For video, omit alternate qualities,
storyboards, cover, and start timestamp.

- [ ] **Step 8: Run all mapper tests**

```bash
./mvnw -Dtest=TelegramHtmlFormatterTest,TelegramCanonicalHashTest,TelegramMessageMapperTest test
```

Expected: PASS without a Telegram account.

- [ ] **Step 9: Commit**

```bash
git add pom.xml src/main/java/org/moera/importer/source/telegram src/test/java/org/moera/importer/source/telegram
git commit -m "feat: map supported Telegram content explicitly"
```

---

### Task 8: Implement TDLight session, channel resolution, polling, live updates, and media download

**Files:**
- Create: `src/main/java/org/moera/importer/config/TelegramProperties.java`
- Create: `src/main/java/org/moera/importer/source/telegram/TelegramClient.java`
- Create: `src/main/java/org/moera/importer/source/telegram/TelegramSourceAdapter.java`
- Create: `src/main/java/org/moera/importer/source/telegram/TelegramMediaDownloader.java`
- Create: `src/main/java/org/moera/importer/source/telegram/TelegramLoginCommand.java`
- Create: `src/test/java/org/moera/importer/source/telegram/TelegramSourceAdapterTest.java`
- Create: `src/test/java/org/moera/importer/source/telegram/TelegramPollingTest.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: `SourceAdapter`, explicit mapping result from Task 7, persistent session/cache/staging config.
- Produces: full `SourceType.TELEGRAM` adapter.

- [ ] **Step 1: Write adapter tests against a fake TelegramClient boundary**

Test public channel resolution, unresolved private invite, access request result, access granted with
`resolvedSourceId`, active/inactive subscription, and `LOST` only for meaningful membership loss rather than transient
connectivity.

- [ ] **Step 2: Run adapter tests and verify failure**

```bash
./mvnw -Dtest=TelegramSourceAdapterTest test
```

Expected: FAIL because client/adapter do not exist.

- [ ] **Step 3: Implement TelegramClient wrapper and typed configuration**

Configuration includes API ID/hash, persistent TDLight database directory, disposable TDLight download cache directory,
grouping threshold, settling delay, polling intervals, and look-behind count. Do not persist login codes or 2FA
passwords.

- [ ] **Step 4: Implement channel resolution/access/subscription**

Public username/link resolution stores stable decimal chat ID. Private invite may leave `sourceId=null`; join approval
maps to `REQUESTED`. Conflicting non-null resolved IDs are treated as permanent consistency errors by core completion
logic.

- [ ] **Step 5: Write failing polling/waterline and skip-semantics tests**

Using fake history pages, assert the adapter:

```text
paginates backwards across short pages
reads through the configured look-behind
returns messages oldest-to-newest
returns new waterline at newest forward-scanned message ID
never changes persistent waterline itself
omits deliberately skipped messages while advancing the returned waterline past them
propagates an unexpected mapper failure instead of silently skipping it
```

- [ ] **Step 6: Implement Telegram polling**

Represent waterline as JSON `{ "messageId": <long> }`. The adapter only returns `PollResult`;
`MessageRepository.ingestPoll` owns the durable advance.

- [ ] **Step 7: Implement ordered live update delivery**

TDLight callbacks enqueue relevant updates. A sequential consumer maps/forwards events per the spec. Handle new
message, content change/re-read, `isPinned` changes, permanent deletion, and membership state changes. Emit
`SourceEvent.MessageChanged` only for `Mapped`; ignore live `Skipped` results. Do not advance waterline on live events.
If a previously persisted message now maps to `Skipped`, do not delete or overwrite its last supported version.

- [ ] **Step 8: Implement media download**

Use stored remote file ID/type to reacquire a TDLib file, synchronously download it on the caller's virtual thread,
copy from TDLib cache to the core-supplied path, then ask TDLib to delete its cache copy.

- [ ] **Step 9: Implement dedicated Telegram login/bootstrap command**

Normal daemon startup must fail readiness clearly if no usable saved session exists; it must not prompt interactively.
The bootstrap command performs phone/code/2FA interaction and writes TDLight session state to the configured persistent
directory.

- [ ] **Step 10: Run Telegram unit/integration-boundary tests**

```bash
./mvnw -Dtest='Telegram*Test' test
```

Expected: PASS without requiring a real Telegram account.

- [ ] **Step 11: Commit**

```bash
git add \
  src/main/java/org/moera/importer/source/telegram \
  src/main/java/org/moera/importer/config \
  src/main/resources/application.yml \
  src/test/java/org/moera/importer/source/telegram
git commit -m "feat: implement Telegram source adapter"
```

---

### Task 9: Implement Moera client/service, structured body composition, and story updates

**Files:**
- Create: `src/main/java/org/moera/importer/moera/MoeraService.java`
- Create: `src/main/java/org/moera/importer/moera/MoeraClientFactory.java`
- Create: `src/main/java/org/moera/importer/moera/MoeraCredentialStore.java`
- Create: `src/main/java/org/moera/importer/moera/DestinationCapabilities.java`
- Create: `src/main/java/org/moera/importer/moera/MediaUpload.java`
- Create: `src/main/java/org/moera/importer/moera/UploadedMedia.java`
- Create: `src/main/java/org/moera/importer/moera/CreatedPosting.java`
- Create: `src/main/java/org/moera/importer/application/PublicationPayload.java`
- Create: `src/main/java/org/moera/importer/application/PublicationPayloadBuilder.java`
- Create: `src/main/java/org/moera/importer/persistence/PublicationContext.java`
- Create: `src/test/java/org/moera/importer/application/PublicationPayloadBuilderTest.java`
- Create: `src/test/java/org/moera/importer/moera/MoeraServiceTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Produces:

```java
DestinationCapabilities MoeraService.capabilities(Destination destination);
MediaUpload MoeraService.uploadMedia(Destination destination, Path file, Media media);
MediaUpload MoeraService.checkMediaCompression(Destination destination, String originalMediaId);
CreatedPosting MoeraService.createPost(Destination destination, PublicationPayload payload);
void MoeraService.updatePost(Destination destination, String postingId, PublicationPayload payload);
void MoeraService.updateStory(Destination destination, String storyId, boolean pinned);
void MoeraService.deletePost(Destination destination, String postingId);
PublicationPayload PublicationPayloadBuilder.build(PublicationContext context, DestinationCapabilities capabilities);

sealed interface MediaUpload {
    record Ready(UploadedMedia media) implements MediaUpload;
    record AwaitingCompression(UploadedMedia original) implements MediaUpload;
}
```

`UploadedMedia` exposes distinct `id` and `hash` values. `MediaUpload.AwaitingCompression` carries the original upload
whose `PrivateMediaFileInfo.uncompressed` flag is true and whose `compressedMediaId` is not ready yet.
`CreatedPosting` exposes the posting ID and returned `FeedReference.storyId` keyed by configured feed, so later pin
changes can address existing stories.

- [ ] **Step 1: Add Moera Java library and write failing payload-builder tests**

Cover same-format preservation, mixed-format conversion to HTML, fallback to plain text when HTML is unsupported,
paragraph boundaries between source messages, media-only payload, earliest `publishedAt`, configured feed names, and
effective pin state (`true` when any non-deleted member is pinned). For Telegram HTML bodies, preserve per-message
forward attribution and insert `<hr>` before a grouped message whose attribution differs from the previous one.

Build structured `Body.linkPreviews` in member/source order, deduplicate by exact URL with the first preview winning,
and omit preview-only media belonging to ignored duplicates. Map `siteName`, `url`, `title`, `description`, and
`publishedAt`; set `imageHash` from the selected uploaded media's `PrivateMediaFileInfo.hash`, never its ID. The
selected preview image remains attached to the posting as an ordinary media item. If that media was skipped or has no
stored hash, retain the textual preview and leave `imageHash` absent.

- [ ] **Step 2: Run payload tests and verify failure**

```bash
./mvnw -Dtest=PublicationPayloadBuilderTest test
```

Expected: FAIL because builder/types do not exist.

- [ ] **Step 3: Implement payload builder**

Do not implement HTML-to-Markdown conversion. Preserve one common supported format, otherwise prefer HTML, otherwise
plain text. Join non-empty source bodies by one paragraph boundary in fixed publication order. Recalculate effective
previews and media from the current non-deleted snapshot on every build, allowing a later duplicate to become active
when the earlier one disappears.

- [ ] **Step 4: Write MoeraService boundary tests**

Use a fake/mockable Moera client boundary to assert:

```text
/features provides sourceFormats and getNodeSettings("media.max-size") provides mediaMaxSize
private media upload always sets downsize=true
upload preserves distinct PrivateMediaFileInfo.id and PrivateMediaFileInfo.hash
uncompressed upload without compressedMediaId returns AwaitingCompression with the original ID/hash
compression check without compressedMediaId remains AwaitingCompression
compression check with compressedMediaId fetches that PrivateMediaFileInfo and returns Ready with its ID/hash
create includes feed publications, earliest publishAt, and StoryAttributes.pinned
create returns feed story IDs
create/update use createPosting/updatePosting, never asynchronous createRemotePosting/updateRemotePosting
update does not rebuild feed publication attributes
updateStory changes only the pin attribute of an existing story
delete not-found is treated as success
media wrong-size maps to terminal TOO_LARGE semantics
```

- [ ] **Step 5: Implement credential resolution and client factory**

`destinations.config` stores a credential reference. Resolve the secret from configured environment/file-backed
deployment secret storage. Never log credential contents. Resolve current node URL by `node_name`; do not cache node
URL permanently.

- [ ] **Step 6: Implement MoeraService**

Cache `/features` capabilities in memory with a bounded TTL. Keep Moera-specific error mapping inside this package.
Represent ambiguous mutating outcomes with a dedicated outcome-unknown exception used by processors. Preserve both
private-media ID/hash values, returned feed/story identity, and repeatable story updates by story ID.

Map an upload result to `MediaUpload.Ready` unless `uncompressed=true` and no compressed file is ready. In the latter
case return `AwaitingCompression`. `checkMediaCompression` calls `getPrivateMediaInfo(originalMediaId, null)`; while
`compressedMediaId` is absent it returns `AwaitingCompression`, otherwise it fetches the compressed file information
and returns `Ready` with the compressed ID/hash. Posting create/update must continue to use synchronous
`createPosting(PostingText)` and `updatePosting(...)`; do not call the remote-posting APIs because they return no
posting ID or trackable result containing it.

- [ ] **Step 7: Run Moera/payload tests**

```bash
./mvnw -Dtest=PublicationPayloadBuilderTest,MoeraServiceTest test
```

Expected: PASS without a real Moera node.

- [ ] **Step 8: Commit**

```bash
git add \
  pom.xml \
  src/main/java/org/moera/importer/moera \
  src/main/java/org/moera/importer/application \
  src/test/java/org/moera/importer/application \
  src/test/java/org/moera/importer/moera
git commit -m "feat: implement Moera delivery boundary"
```

---

### Task 10: Implement staging and publication-media delivery workflow

**Files:**
- Create: `src/main/java/org/moera/importer/staging/StagingFileManager.java`
- Create: `src/main/java/org/moera/importer/application/PublicationMediaProcessor.java`
- Create: `src/main/java/org/moera/importer/scheduling/PublicationMediaScheduler.java`
- Create: `src/test/java/org/moera/importer/staging/StagingFileManagerTest.java`
- Create: `src/test/java/org/moera/importer/application/PublicationMediaProcessorTest.java`
- Modify: `src/main/java/org/moera/importer/persistence/PublicationMediaRepository.java`
- Modify: `src/main/java/org/moera/importer/config/WorkerProperties.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Produces:

```java
Path StagingFileManager.path(long publicationId, long mediaId, byte[] sourceHash);
List<PublicationMediaClaim> PublicationMediaRepository.claimPending(int limit, Duration lease);
PublicationMediaContext PublicationMediaRepository.loadContext(long publicationId, long mediaId);
boolean PublicationMediaRepository.awaitingCompression(
    long publicationId, long mediaId, UUID token, byte[] expectedSourceHash,
    String originalMediaId, JsonNode destinationData, Instant nextAttemptAt);
boolean PublicationMediaRepository.uploaded(
    long publicationId, long mediaId, UUID token, byte[] expectedSourceHash,
    String destinationMediaId, JsonNode destinationData);
boolean PublicationMediaRepository.skipped(
    long publicationId, long mediaId, UUID token, byte[] expectedSourceHash, MediaSkipReason reason);
boolean PublicationMediaRepository.retry(
    long publicationId, long mediaId, UUID token, Instant nextAttemptAt, String error);
boolean PublicationMediaRepository.fail(long publicationId, long mediaId, UUID token, String error);
```

- [ ] **Step 1: Write deterministic staging-path tests**

Assert same `(publicationId, mediaId, sourceHash)` produces the same path, a changed hash produces a different path,
and generated paths cannot escape the configured staging root.

- [ ] **Step 2: Implement StagingFileManager**

Use lower-case SHA-256 hex in filename. Create parent directories safely. Do not store staging paths in PostgreSQL.

- [ ] **Step 3: Write PublicationMediaProcessor tests**

Cover:

```text
known size > max       -> SKIPPED/TOO_LARGE without download
actual size > max      -> SKIPPED/TOO_LARGE after download
existing staging file  -> reused on retry
ordinary upload        -> UPLOADED + staging deleted; ID column stores upload id and JSON stores upload hash
compression required   -> PENDING + original ID/hash persisted + lease cleared + staging deleted
compression not ready  -> no download/upload; PENDING is rescheduled with no last_error
compression ready      -> UPLOADED with compressed ID/hash replacing the original ID/hash
temporary upload fail  -> retry + staging retained
permanent fail         -> automatic scheduling stops + staging deleted
source_hash changes    -> stale wait/final completion rejected and intermediate destination data cleared
```

- [ ] **Step 4: Implement media claim/completion repository operations**

Claim only due `PENDING` rows with available/expired lease, increment attempts at claim time, and complete
conditionally by token. Include current `destination_media_id` and `destination_data` in the claim/context so the
processor can recognize a compression wait without re-uploading. `awaitingCompression`, `uploaded`, and `skipped`
additionally verify current `Media.source_hash` equals the expected hash.

`awaitingCompression` keeps `status=PENDING`, stores the original upload ID/hash and a `compressionPending=true`
marker, clears the lease and `last_error`, and sets the fixed next check time. It is a normal wait transition, not a
failure transition. Resetting a row after a source-hash change must clear its destination ID/data.

- [ ] **Step 5: Implement PublicationMediaProcessor**

Load source adapter by channel source type, load Moera capabilities, perform pre/post-download size checks, download
only when staging is absent, upload with `MoeraService`, and map failures through shared retry policy. Pass
`UploadedMedia.id` as `destinationMediaId` and serialize `UploadedMedia.hash` into `destinationData`; never substitute
one for the other. The same uploaded row can therefore be attached normally and referenced by link-preview
`imageHash` later.

When upload returns `AwaitingCompression`, conditionally persist the original ID/hash, schedule the next check using
`WorkerProperties.mediaCompressionPollInterval` (default `10s`), and delete staging after the transition attempt. On a
claimed row marked `compressionPending`, skip source download/upload and call `checkMediaCompression`. If it still
returns `AwaitingCompression`, schedule another fixed check without recording an error; if it returns `Ready`, replace
the original destination ID/hash with the compressed values and mark `UPLOADED`. Do not hold the worker, database
transaction, or lease between checks.

- [ ] **Step 6: Implement thin scheduler**

Claim a configured batch and dispatch processor calls on the shared virtual-thread executor.

- [ ] **Step 7: Run tests**

```bash
./mvnw -Dtest=StagingFileManagerTest,PublicationMediaProcessorTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add \
  src/main/java/org/moera/importer/staging \
  src/main/java/org/moera/importer/application/PublicationMediaProcessor.java \
  src/main/java/org/moera/importer/scheduling/PublicationMediaScheduler.java \
  src/main/java/org/moera/importer/persistence/PublicationMediaRepository.java \
  src/main/java/org/moera/importer/config/WorkerProperties.java \
  src/main/resources/application.yml \
  src/test/java/org/moera/importer
git commit -m "feat: deliver publication media"
```

---

### Task 11: Implement publication snapshot, posting, and pin-delivery workflow

**Files:**
- Create: `src/main/java/org/moera/importer/application/PublicationProcessor.java`
- Create: `src/main/java/org/moera/importer/scheduling/PublicationScheduler.java`
- Modify: `src/main/java/org/moera/importer/persistence/PublicationContext.java`
- Create: `src/test/java/org/moera/importer/application/PublicationProcessorTest.java`
- Create: `src/test/java/org/moera/importer/persistence/PublicationRevisionRaceTest.java`
- Modify: `src/main/java/org/moera/importer/persistence/PublicationRepository.java`

**Interfaces:**
- Produces:

```java
List<PublicationClaim> PublicationRepository.claimReady(int limit, Duration lease);
PublicationContext PublicationRepository.loadContext(long publicationId, int targetRevision);
boolean PublicationRepository.created(
    long publicationId, UUID token, int targetRevision, String postingId, JsonNode destinationData);
boolean PublicationRepository.updated(long publicationId, UUID token, int targetRevision, JsonNode destinationData);
boolean PublicationRepository.deleted(long publicationId, UUID token, int targetRevision, JsonNode destinationData);
boolean PublicationRepository.retry(long publicationId, UUID token, Instant nextAttemptAt, String error);
boolean PublicationRepository.fail(long publicationId, UUID token, String error);
```

- [ ] **Step 1: Write failing ready-claim tests**

Assert a publication is claimable only when:

```text
revision IS DISTINCT FROM published_revision
next_attempt_at is due
no current publication_media row is PENDING
PENDING compression wait keeps the publication unclaimable even though the media row already has a destination_media_id
lease is absent or expired
```

- [ ] **Step 2: Implement publication claim**

Claim using `FOR UPDATE SKIP LOCKED`, set token/expiry, increment attempts, and capture `targetRevision` in returned
`PublicationClaim`.

- [ ] **Step 3: Write revision-snapshot race test**

Arrange revision 5, claim target 5, load context, then ingest a source edit that advances stored publication revision
to 6. Complete target 5 and assert:

```text
revision = 6
published_revision = 5
next_attempt_at is armed for revision 6
```

- [ ] **Step 4: Implement `loadContext` consistent snapshot**

Use a short transaction and a row lock sufficient to read one coherent target revision into memory. Release transaction
before calling Moera. Include ordered member bodies and `source_data`, deleted/pinned state, ordered link previews,
their optional media relations, and current uploaded media ID/hash data. Keep `DSLContext`, generated records, and jOOQ
types inside the persistence layer; expose immutable application values to the payload builder.

- [ ] **Step 5: Write PublicationProcessor decision tests**

Cover:

```text
no destination_post_id + non-empty payload -> create
existing post + non-empty payload           -> update
existing post + empty payload               -> delete
no post + empty payload                     -> no external call, mark revision published
outcome unknown on create                    -> retry + distinct metric/log path
create                                       -> persist posting ID, feed story IDs, and delivered pin state
pin/unpin after create                       -> update every stored feed story by story ID
content and pin change                       -> update post, then stories, then complete revision
partial story-update failure                 -> retry without completing target revision
```

- [ ] **Step 6: Implement PublicationProcessor and scheduler**

Build payload from the in-memory context and Moera capabilities, perform the selected Moera posting operation, and
then synchronize pin state through `MoeraService.updateStory` when stored story IDs exist and the effective state
changed. On create, persist `FeedReference.storyId` values keyed by feed and the delivered pin state in
`publications.destination_data`. Do not attempt to change feed membership or `publishAt` during posting updates.
Complete only the captured target revision using the claim token and only after every required posting/story call
succeeds.

- [ ] **Step 7: Run publication tests**

```bash
./mvnw -Dtest=PublicationProcessorTest,PublicationRevisionRaceTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add \
  src/main/java/org/moera/importer/application/PublicationProcessor.java \
  src/main/java/org/moera/importer/scheduling/PublicationScheduler.java \
  src/main/java/org/moera/importer/persistence \
  src/test/java/org/moera/importer
git commit -m "feat: deliver publication revisions"
```

---

### Task 12: Add observability, health, staging cleanup, startup ordering, and graceful shutdown

**Files:**
- Create: `src/main/java/org/moera/importer/staging/StagingCleanup.java`
- Create: `src/main/java/org/moera/importer/config/StorageProperties.java`
- Create: `src/main/java/org/moera/importer/config/RetryProperties.java`
- Create: `src/main/java/org/moera/importer/config/ReadinessHealthIndicator.java`
- Create: `src/main/java/org/moera/importer/config/StartupCoordinator.java`
- Create: `src/main/java/org/moera/importer/config/WorkerExecutorConfiguration.java`
- Create: `src/test/java/org/moera/importer/staging/StagingCleanupTest.java`
- Create: `src/test/java/org/moera/importer/config/ReadinessHealthIndicatorTest.java`
- Modify: processors/schedulers to add structured logs and Micrometer meters
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Produces: startup sequence and readiness contract from spec; periodic cleanup; low-cardinality metrics; bounded
  graceful shutdown.

- [ ] **Step 1: Write failing staging cleanup tests**

Create staging files representing pending media that still needs upload, pending media already waiting for Moera
compression, uploaded/skipped media, deleted DB rows, changed source hashes, and old orphans. Assert cleanup retains
only current upload-pending files plus files inside the configured grace period; compression-pending rows do not retain
staging because their original upload ID is already durable.

- [ ] **Step 2: Implement StagingCleanup**

Run once during startup before schedulers become active and periodically afterwards. Database checks must be batched
enough to avoid one query per filesystem file for large directories.

- [ ] **Step 3: Write health/readiness tests**

Assert liveness is independent of temporary Telegram/Moera outages. Readiness is down until PostgreSQL/Flyway are ready
and TDLight initialization reports usable daemon state.

- [ ] **Step 4: Implement startup coordinator**

Enforce sequence:

```text
configuration -> database/Flyway -> repositories -> TDLight init
  -> SourceAdapter.start -> staging cleanup -> scheduler enablement
  -> readiness UP
```

Schedulers must have an enable gate rather than racing application startup.

- [ ] **Step 5: Add structured logging**

Add context fields where applicable:

```text
operation, channel_id, publication_id, media_id,
processing_token, attempt, target_revision, result
```

Never log forbidden sensitive values from `AGENTS.md`.

For a deliberate Telegram skip, log only safe channel/message identity, runtime content type, and the low-cardinality
skip reason. Never log formatted text, captions, attribution details, preview URLs, invite links, or callback data.

- [ ] **Step 6: Add Micrometer counters/timers/gauges**

Implement the metrics named in the architecture spec, using only low-cardinality labels. Include
`publication_unknown_outcomes_total`, `telegram_messages_skipped_total`, and overdue-work age gauges. The Telegram skip
counter may be labeled by stable content family and `TelegramSkipReason`, but never by channel/message IDs, content
text, URLs, node names, or processing tokens.

- [ ] **Step 7: Implement graceful shutdown**

Stop new claims first, stop source adapters/live intake, wait a configured bounded grace period for workers, then allow
process exit. Do not mass-clear or roll back leases.

- [ ] **Step 8: Run focused tests**

```bash
./mvnw -Dtest=StagingCleanupTest,ReadinessHealthIndicatorTest test
```

Expected: PASS.

- [ ] **Step 9: Run the full default suite**

```bash
./mvnw clean verify
```

Expected: all unit and Testcontainers integration tests pass; real Telegram/Moera smoke tests remain excluded by
default.

- [ ] **Step 10: Commit**

```bash
git add src/main src/test
git commit -m "feat: add recovery and observability"
```

---

### Task 13: Add an end-to-end application test and operational documentation

**Files:**
- Create: `src/test/java/org/moera/importer/ImportPipelineTest.java`
- Create: `doc/operations.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: complete application from Tasks 1-12.
- Produces: deterministic proof of the core pipeline and operator instructions for initial Telegram login, filesystem
  permissions, PostgreSQL, secrets, health, and smoke tests.

- [ ] **Step 1: Write end-to-end pipeline test**

Use Testcontainers PostgreSQL, a fake source adapter, and a fake Moera client/service boundary. Exercise:

```text
channel poll
-> message/media/link-preview ingest
-> publication grouping
-> media processing
-> publication create
-> source edit with preview/pin change
-> publication and feed-story update
-> source deletion
-> publication delete when empty
```

Assert waterline advancement past a deliberately skipped Telegram message, revision transitions, immutable publication
membership, preview-image upload ID/hash handling, first-URL-wins preview composition, and the durable video path from
an original uncompressed upload through at least one pending compression check to final compressed ID/hash without a
second source download/upload. Also assert stored story IDs, pin/unpin calls, and final destination operations use
synchronous posting create/update only after all compression waits are terminal.

- [ ] **Step 2: Run the pipeline test and verify any missing wiring fails**

```bash
./mvnw -Dtest=ImportPipelineTest test
```

Expected before final wiring fixes: FAIL at the first missing Spring/application integration point.

- [ ] **Step 3: Add the minimal wiring required for the test to pass**

Do not introduce new architecture. Fix only dependency injection, scheduler gates, test fakes, or configuration wiring
required to connect already-designed components.

- [ ] **Step 4: Run full verification**

```bash
./mvnw clean verify
```

Expected: PASS with default test profile and no real external accounts required.

- [ ] **Step 5: Write operational documentation**

Document exact commands/configuration for:

```text
building and testing
PostgreSQL connection
TDLight state/cache/staging directories and permissions
one-time Telegram login/bootstrap
Moera credential references
starting the daemon
Actuator health/readiness
Prometheus endpoint
opt-in Telegram/Moera smoke tests
```

- [ ] **Step 6: Cross-check architecture and implementation**

Read `doc/architecture/import-service.md` from start to finish. For every normative section, point to the
implementation/test that satisfies it. If implementation intentionally differs, update the architecture first and
obtain approval rather than silently changing semantics.

- [ ] **Step 7: Commit**

```bash
git add README.md doc src/test/java/org/moera/importer/ImportPipelineTest.java
git commit -m "test: verify complete import pipeline"
```
