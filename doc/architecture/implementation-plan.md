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

**Spec:** `docs/architecture/import-service.md`

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

### Task 6: Implement Telegram message mapping and canonical hashes

**Files:**
- Create: `src/main/java/org/moera/importer/source/telegram/TelegramMessageMapper.java`
- Create: `src/main/java/org/moera/importer/source/telegram/TelegramHtmlFormatter.java`
- Create: `src/main/java/org/moera/importer/source/telegram/TelegramCanonicalHash.java`
- Create: `src/test/java/org/moera/importer/source/telegram/TelegramMessageMapperTest.java`
- Create: `src/test/java/org/moera/importer/source/telegram/TelegramHtmlFormatterTest.java`

**Interfaces:**
- Produces:

```java
SourceMessage TelegramMessageMapper.map(TdApi.Message message);
String TelegramHtmlFormatter.format(TdApi.FormattedText text);
byte[] TelegramCanonicalHash.body(TdApi.FormattedText text);
byte[] TelegramCanonicalHash.media(Telegram media metadata input);
```

- [ ] **Step 1: Add TDLight dependency and write failing formatted-text tests**

Create TDLib `FormattedText` fixtures for plain text, bold, italic, link, code/pre, and overlapping/nested entities
supported by Telegram. Include a surrogate-pair/emoji case proving offsets are handled with TDLib/Java UTF-16
semantics.

- [ ] **Step 2: Run formatter tests and verify failure**

```bash
./mvnw -Dtest=TelegramHtmlFormatterTest test
```

Expected: FAIL because formatter does not exist.

- [ ] **Step 3: Implement sanitized HTML formatter**

Escape raw text and attribute values. Convert supported entities to safe HTML tags/links. Do not pass arbitrary source
HTML through unsanitized.

- [ ] **Step 4: Write canonical body-hash tests**

Assert identical text/entities produce identical SHA-256, while formatting-only changes produce a different hash even
when visible text is the same.

- [ ] **Step 5: Implement canonical hashing**

Serialize text plus ordered entity `(offset,length,type,semantic data)` into an unambiguous byte representation, then
SHA-256 it. Do not hash generated HTML.

- [ ] **Step 6: Write message/media mapping tests**

Cover text, photo caption, video/document metadata, media album ID in `source_data`, exact file size, MIME type,
filename, remote stable ID, remote reacquisition ID, and source media hash.

- [ ] **Step 7: Implement TelegramMessageMapper**

Use decimal `message.id` as `SourceMessage.sourceId`, `message.date` as `publishedAt`, HTML body format, and universal
`SourceMedia` metadata without downloading files.

- [ ] **Step 8: Run mapper tests**

```bash
./mvnw -Dtest=TelegramHtmlFormatterTest,TelegramMessageMapperTest test
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add pom.xml src/main/java/org/moera/importer/source/telegram src/test/java/org/moera/importer/source/telegram
git commit -m "feat: map Telegram messages into source model"
```

---

### Task 7: Implement TDLight session, channel resolution, polling, live updates, and media download

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
- Consumes: `SourceAdapter`, mapper from Task 6, persistent session/cache/staging config.
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

- [ ] **Step 5: Write failing polling/waterline tests**

Using fake history pages, assert the adapter:

```text
paginates backwards across short pages
reads through the configured look-behind
returns messages oldest-to-newest
returns new waterline at newest forward-scanned message ID
never changes persistent waterline itself
```

- [ ] **Step 6: Implement Telegram polling**

Represent waterline as JSON `{ "messageId": <long> }`. The adapter only returns `PollResult`;
`MessageRepository.ingestPoll` owns the durable advance.

- [ ] **Step 7: Implement ordered live update delivery**

TDLight callbacks enqueue relevant updates. A sequential consumer maps/forwards events per the spec. Handle new
message, content change/re-read, permanent deletion, and membership state changes. Do not advance waterline on live
events.

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

### Task 8: Implement Moera client/service, capabilities, body composition, and posting decisions

**Files:**
- Create: `src/main/java/org/moera/importer/moera/MoeraService.java`
- Create: `src/main/java/org/moera/importer/moera/MoeraClientFactory.java`
- Create: `src/main/java/org/moera/importer/moera/MoeraCredentialStore.java`
- Create: `src/main/java/org/moera/importer/moera/DestinationCapabilities.java`
- Create: `src/main/java/org/moera/importer/moera/UploadedMedia.java`
- Create: `src/main/java/org/moera/importer/moera/CreatedPosting.java`
- Create: `src/main/java/org/moera/importer/application/PublicationPayload.java`
- Create: `src/main/java/org/moera/importer/application/PublicationPayloadBuilder.java`
- Create: `src/test/java/org/moera/importer/application/PublicationPayloadBuilderTest.java`
- Create: `src/test/java/org/moera/importer/moera/MoeraServiceTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Produces:

```java
DestinationCapabilities MoeraService.capabilities(Destination destination);
UploadedMedia MoeraService.uploadMedia(Destination destination, Path file, Media media);
CreatedPosting MoeraService.createPost(Destination destination, PublicationPayload payload);
void MoeraService.updatePost(Destination destination, String postingId, PublicationPayload payload);
void MoeraService.deletePost(Destination destination, String postingId);
PublicationPayload PublicationPayloadBuilder.build(PublicationContext context, DestinationCapabilities capabilities);
```

- [ ] **Step 1: Add Moera Java library and write failing payload-builder tests**

Cover same-format preservation, mixed-format conversion to HTML, fallback to plain text when HTML is unsupported,
paragraph boundaries between source messages, media-only payload, earliest `publishedAt`, and configured feed names.

- [ ] **Step 2: Run payload tests and verify failure**

```bash
./mvnw -Dtest=PublicationPayloadBuilderTest test
```

Expected: FAIL because builder/types do not exist.

- [ ] **Step 3: Implement payload builder**

Do not implement HTML-to-Markdown conversion. Preserve one common supported format, otherwise prefer HTML, otherwise
plain text. Join non-empty source bodies by one paragraph boundary in fixed publication order.

- [ ] **Step 4: Write MoeraService boundary tests**

Use a fake/mockable Moera client boundary to assert:

```text
/features provides mediaMaxSize + sourceFormats
private media upload always sets downsize=true
create includes feed publications and earliest publishAt
update does not rebuild feed publication attributes
delete not-found is treated as success
media wrong-size maps to terminal TOO_LARGE semantics
```

- [ ] **Step 5: Implement credential resolution and client factory**

`destinations.config` stores a credential reference. Resolve the secret from configured environment/file-backed
deployment secret storage. Never log credential contents. Resolve current node URL by `node_name`; do not cache node
URL permanently.

- [ ] **Step 6: Implement MoeraService**

Cache `/features` capabilities in memory with a bounded TTL. Keep Moera-specific error mapping inside this package.
Represent ambiguous mutating outcomes with a dedicated outcome-unknown exception used by processors.

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

### Task 9: Implement staging and publication-media delivery workflow

**Files:**
- Create: `src/main/java/org/moera/importer/staging/StagingFileManager.java`
- Create: `src/main/java/org/moera/importer/application/PublicationMediaProcessor.java`
- Create: `src/main/java/org/moera/importer/scheduling/PublicationMediaScheduler.java`
- Create: `src/test/java/org/moera/importer/staging/StagingFileManagerTest.java`
- Create: `src/test/java/org/moera/importer/application/PublicationMediaProcessorTest.java`
- Modify: `src/main/java/org/moera/importer/persistence/PublicationMediaRepository.java`

**Interfaces:**
- Produces:

```java
Path StagingFileManager.path(long publicationId, long mediaId, byte[] sourceHash);
List<PublicationMediaClaim> PublicationMediaRepository.claimPending(int limit, Duration lease);
PublicationMediaContext PublicationMediaRepository.loadContext(long publicationId, long mediaId);
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
successful upload      -> UPLOADED + staging deleted
temporary upload fail  -> retry + staging retained
permanent fail         -> automatic scheduling stops + staging deleted
source_hash changes    -> stale completion rejected
```

- [ ] **Step 4: Implement media claim/completion repository operations**

Claim only due `PENDING` rows with available/expired lease, increment attempts at claim time, and complete
conditionally by token. `uploaded/skipped` additionally verify current `Media.source_hash` equals the expected hash.

- [ ] **Step 5: Implement PublicationMediaProcessor**

Load source adapter by channel source type, load Moera capabilities, perform pre/post-download size checks, download
only when staging is absent, upload with `MoeraService`, and map failures through shared retry policy.

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
  src/test/java/org/moera/importer
git commit -m "feat: deliver publication media"
```

---

### Task 10: Implement publication snapshot/delivery workflow

**Files:**
- Create: `src/main/java/org/moera/importer/application/PublicationProcessor.java`
- Create: `src/main/java/org/moera/importer/scheduling/PublicationScheduler.java`
- Create: `src/main/java/org/moera/importer/persistence/PublicationContext.java`
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
before calling Moera.

- [ ] **Step 5: Write PublicationProcessor decision tests**

Cover:

```text
no destination_post_id + non-empty payload -> create
existing post + non-empty payload           -> update
existing post + empty payload               -> delete
no post + empty payload                     -> no external call, mark revision published
outcome unknown on create                    -> retry + distinct metric/log path
```

- [ ] **Step 6: Implement PublicationProcessor and scheduler**

Build payload from the in-memory context and Moera capabilities, perform the selected Moera operation, and complete
only the captured target revision using the claim token.

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

### Task 11: Add observability, health, staging cleanup, startup ordering, and graceful shutdown

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

Create staging files representing current pending media, uploaded/skipped media, deleted DB rows, changed source
hashes, and old orphans. Assert cleanup retains only current pending files plus files inside the configured grace
period.

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

- [ ] **Step 6: Add Micrometer counters/timers/gauges**

Implement the metrics named in the architecture spec, using only low-cardinality labels. Include
`publication_unknown_outcomes_total` and overdue-work age gauges.

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

### Task 12: Add an end-to-end application test and operational documentation

**Files:**
- Create: `src/test/java/org/moera/importer/ImportPipelineTest.java`
- Create: `docs/operations.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: complete application from Tasks 1-11.
- Produces: deterministic proof of the core pipeline and operator instructions for initial Telegram login, filesystem
  permissions, PostgreSQL, secrets, health, and smoke tests.

- [ ] **Step 1: Write end-to-end pipeline test**

Use Testcontainers PostgreSQL, a fake source adapter, and a fake Moera client/service boundary. Exercise:

```text
channel poll
-> message/media ingest
-> publication grouping
-> media processing
-> publication create
-> source edit
-> publication update
-> source deletion
-> publication delete when empty
```

Assert waterline advancement, revision transitions, immutable publication membership, and final destination operations.

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

Read `docs/architecture/import-service.md` from start to finish. For every normative section, point to the
implementation/test that satisfies it. If implementation intentionally differs, update the architecture first and
obtain approval rather than silently changing semantics.

- [ ] **Step 7: Commit**

```bash
git add README.md docs src/test/java/org/moera/importer/ImportPipelineTest.java
git commit -m "test: verify complete import pipeline"
```
