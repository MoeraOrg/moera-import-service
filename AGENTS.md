# Moera Import Service — Agent Instructions

Read `doc/architecture/import-service.md` before making architectural or persistence changes. It is the canonical
design specification.

## Project constraints

- Use Maven.
- Use Java 25 and Spring.
- Use PostgreSQL for durable state.
- Flyway SQL migrations are the **only source of truth** for the database schema.
- Generate jOOQ from the Flyway-defined PostgreSQL schema. Keep `DSLContext`, generated tables/records, and jOOQ DSL
  inside the persistence layer.
- Repositories are concrete classes. Do not add repository interfaces or ORM/Hibernate abstractions.
- Database table names are plural. `media` is already plural.
- Use Java records/immutable values for domain and boundary DTOs where practical.
- Use Java virtual threads for blocking database/network/file work. Do not introduce Reactor/reactive plumbing unless
  the architecture is explicitly changed.
- Do not add a generic job queue or durable event bus. `channels`, `publication_media`, and `publications` are the
  durable work queues described by the spec.
- Do not hold a PostgreSQL transaction open across Telegram, filesystem, or Moera network calls.
- External work uses lease token + lease expiry + conditional completion. Pure DB grouping uses short row-locking
  transactions.

## Source model

- `Channel` is an external source channel/feed. `Message` belongs to exactly one `Channel`.
- Telegram is the only source type in the initial schema. Do not add RSS/Mastodon enum values until those adapters are
  actually implemented.
- Source-specific state belongs in JSONB and `SourceAdapter`; do not promote Telegram-specific concepts into universal
  columns without an explicit design change.
- `Message.revision` starts at 0 and stores only the latest revision. There is no message revision history table.
- `body`, `body_format`, `source_body_hash`, and `Media.source_hash` are non-null according to the spec.
- Publication membership is immutable after creation. Do not implement reconciliation/regrouping.
- Grouping threshold, settling delay, polling intervals, look-behind, and source grouping hints belong to
  `SourceAdapter`.

## Moera delivery

- Moera is the only planned destination. Do not add a `DestinationAdapter` abstraction or destination type
  discriminator.
- Destination identity is `node_name` (maximum 135 characters).
- `MoeraService` owns node resolution, credentials, `/features`, node settings, private media upload, and posting CRUD.
- Always request `downsize=true` on Moera media upload.
- Respect `media.max-size` from `getNodeSettings()` and `posting.sourceFormats` from `/features`.
- `publication_media.destination_media_id` is at most 40 characters.
- Moera create currently lacks idempotency. Unknown create outcomes are retried and separately diagnosed; do not
  pretend exactly-once creation is guaranteed.

## Persistence and concurrency

- `next_attempt_at = NULL` means the scheduler must not run that item automatically now.
- Increment `attempts` when an attempt is claimed, not only after failure.
- Stale workers must not overwrite newer work: completion must match `processing_token`; media completion must also
  match the expected `source_hash` where specified.
- Live source updates never advance the Telegram waterline. Only a successfully persisted poll batch advances it.
- Do not over-engineer database constraints. Use direct PK/FK/UNIQUE/NOT NULL/simple CHECK constraints; keep complex
  domain invariants in Java as specified.

## Testing

- Use JUnit 5.
- Use real PostgreSQL via Testcontainers for repository/integration tests. Do not use H2 as a PostgreSQL substitute.
- Apply the production Flyway migrations in integration tests.
- Unit-test pure grouping, retry, hash, body-composition, mapper, and decision logic without Spring where possible.
- Real Telegram and real Moera tests are opt-in smoke/integration tests, not default CI tests.
- For implementation work, follow test-driven development: failing test first, verify failure, implement minimal
  behavior, verify pass, then commit.
- The local container runtime is rootless Podman. Testcontainers uses its Docker-compatible API; do not assume
  Docker Engine is installed or try to start it.
- Run Maven tests that use Mockito or Testcontainers outside the agent sandbox from the first attempt. With
  `exec_command`, use `sandbox_permissions="require_escalated"` for `./mvnw -o test` (or the needed test selection).
  The sandbox blocks Mockito's Java-agent attachment and access to the Podman API socket. Do not first run the suite
  inside the sandbox and rediscover these known environment failures.
- Reuse the existing Podman/Testcontainers configuration. This workstation's `~/.testcontainers.properties` points
  to `unix:///run/user/1000/podman/podman.sock`, and `TESTCONTAINERS_RYUK_DISABLED=true` is already configured.
  Preserve an existing `DOCKER_HOST` override. If configuration is missing, use the rootless Podman setup in
  `doc/operations.md`; do not replace Podman with Docker or change project dependencies to fix sandbox restrictions.

## Security and observability

- Do not log message bodies, Telegram invite links, verification codes, 2FA passwords, tokens, or Moera credentials.
- Put diagnostic fields directly in the log message text. Do not rely on structured key-values that may be omitted by
  the configured log format.
- Do not store Moera credentials directly in PostgreSQL JSONB; store a credential reference and resolve the secret from
  deployment secret storage.
- TDLight authorization/session state is persistent filesystem state and is credential-equivalent.
- Metrics must avoid high-cardinality labels such as channel IDs, publication IDs, media IDs, node names, or processing
  tokens.

## Change discipline

- If implementation reveals that the agreed architecture must change, update `doc/architecture/import-service.md`
  first and call out the design change explicitly.
- Do not add speculative abstractions for future RSS/Mastodon support. Add only the extension points already defined by
  the spec.
- Keep files focused and prefer small, explicit classes over generic frameworks.

## Language

- Setters and getters should not contain any logic besides copying values.
- If a lambda can be replaced with a method reference, replace it.
- Do not use `@Inject` on constructors; use field injection with `@Inject`.
- Name an injected field after its class, with the first letter lowercased.
- Always add an empty line after the opening brace and before the closing brace of a class declaration if the class
  contains any fields or methods.
- If a record declaration contains no methods, do not put an empty line between the opening and closing braces. Keep
   the opening and closing braces on separate lines.
- Use ObjectUtils.isEmpty(s) instead of s == null || s.isEmpty().

## Documents

- Keep all project documentation under `doc/`. Do not create documentation files in `docs/` or elsewhere.
- Format all documents with a maximum line length of 120 characters.
