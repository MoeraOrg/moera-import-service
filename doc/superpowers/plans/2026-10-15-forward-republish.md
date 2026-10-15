# Forward republish implementation plan

**Goal:** Republish known Telegram originals through Moera stories, reuse stories for later forwards, and remove
forwarding URLs independently on deletion.

**Architecture:** Extend the existing publication queue with republish owners and linked immutable publications.
Persist original membership and asynchronous discovery state. Keep all jOOQ in concrete persistence repositories and
all Moera calls outside transactions.

**Tech stack:** Java 25, Spring, Maven, Flyway, PostgreSQL, generated jOOQ, JUnit 5, rootless Podman/Testcontainers.

**Spec:** `doc/architecture/import-service.md`, section 25.1.

**Execution:** Implement in the current clean checkout, using TDD and one final independent review.

## Constraints and review focus

- Preserve immutable publication membership, token-conditional completions, and exact target-revision completion.
- Story discovery must survive restarts, partial feed discovery, and deletion of every forwarded message.
- Late forwards of another original member must reuse the owner even while discovery is in progress.
- Ambiguous republish outcomes must resume polling; known rejected temporary requests may retry sending.
- Unresolved originals keep attributed-copy delivery; different originals must not lose content through grouping.
- No new generic work queue, network calls in transactions, or redundant media uploads.

## Task 1: Telegram channel-origin metadata

Files: `TelegramMessageMapper.java`, `TelegramForwardAttribution.java`, `TelegramMessageMapperTest.java`.

- [x] Add tests for channel-origin IDs with public, private, and unavailable metadata.
- [x] Run selected mapper tests; verify missing-origin assertions fail.
- [x] Preserve channel-origin chat/message TDLib IDs in source JSONB; keep attribution rendering unchanged.
- [x] Run mapper and canonical-hash tests; verify pass.

## Task 2: Durable republish publications

Files: `V014__publication_republish.sql`, `PublicationRepublishRepository.java`, `RepublishState.java`,
`PublicationRepository.java`, `PublicationContext.java`, `PublicationMediaRepository.java`, `MessageRepository.java`,
`PublicationRepublishRepositoryTest.java`.

- [x] Add PostgreSQL tests for original resolution, full membership snapshots, late groups, fallback, and deletions.
- [x] Run selected tests; verify republish behavior is absent.
- [x] Add the migration and regenerate jOOQ through Maven from the Flyway schema.
- [x] Resolve originals and split groups by target; initialize or attach to one owner per mirror/node/posting.
- [x] Load owner snapshots across linked publications, skip republish media, and ignore forward content edits.
- [x] Persist request time/URIs and partial story identities conditionally; exclude linked rows from claims.
- [x] Test token rejection, changed revisions, deadline persistence, and rearming after deletion; verify pass.

## Task 3: Moera republish boundary

Files: `MoeraService.java`, `MoeraRepublishServiceTest.java`, `MoeraServiceTest.java`.

- [x] Test local/remote republish attributes, filtered discovery, URI updates, and idempotent story deletion.
- [x] Run selected tests; verify missing boundary operations fail.
- [x] Add methods for republish, discovery by original identity/feed, URI replacement, and story deletion.
- [x] Run selected tests; verify pass.

## Task 4: Delivery and regression verification

Files: `PublicationRepublishProcessor.java`, `PublicationProcessor.java`, `PublicationRepublishProcessorTest.java`,
`PublicationRepublishIntegrationTest.java`.

- [x] Test first request, pending/partial discovery, timeout, restart, stale tokens, and unknown outcomes.
- [x] Test URI addition/removal and last-URL story deletion without original-post mutations.
- [x] Run selected tests; verify absent delivery behavior fails.
- [x] Dispatch republish contexts before ordinary payload/capability handling and implement durable polling.
- [x] Run all Maven tests outside the sandbox; inspect results and review the complete diff.
- [x] Address material review findings with failing regression tests; rerun required checks and commit.

## Verification record

- Baseline: `./mvnw -o test` passed, 519 tests.
- Task 1: new mapper assertions failed for missing origin IDs; mapper/hash tests then passed.
- Task 2: repository tests failed for absent republish, reuse, and edit behavior; selected mapper and repository
  regression run then passed, 94 tests, using production Flyway migrations on PostgreSQL.
- Task 3: Moera boundary tests first failed for missing operations, then passed with existing Moera tests,
  36 tests in total.
- Task 4: processor tests first failed for the missing workflow, then passed with ordinary publication tests.
- Independent review identified interrupted deletion and partial-discovery cleanup defects. Both were reproduced
  by PostgreSQL regressions before fixes.
- Final: fixed interrupted deletion and cleanup at discovery timeout; verified URL synchronization despite missing
  feeds/lookup failures, retry backoff across the deadline, and rearming known rejected requests, RED to GREEN.
- The deadline regression uses a one-millisecond remaining interval so configured retry jitter cannot invalidate
  its expected clamp.
- Final verification: `./mvnw -o test` passed, 565 tests, 0 failures, 0 errors, 0 skipped. Checkstyle and jOOQ generation
  ran in that build; integration tests applied the production Flyway migrations to PostgreSQL through rootless Podman.
- `git diff --check` passed. Documentation line lengths were checked against 120 characters.
- Live Telegram/Moera smoke tests remain opt-in and were not run; API behavior was checked against local library and
  server sources. No production-scale benchmark was run.
