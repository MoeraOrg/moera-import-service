# Moera Import Service

Moera Import Service mirrors external channels into Moera. The initial implementation imports Telegram channels,
stores durable state in PostgreSQL, and delivers publications and media to a Moera node.

The service requires Java 25 and PostgreSQL. Build and run the default test suite with:

```bash
./mvnw clean verify
```

The integration tests use Testcontainers and therefore require a Docker-compatible container API. They do not use
real Telegram or Moera accounts.

For local development, copy `.env.local.example` to `.env.local`, fill in the database credentials, and run:

```bash
./run
```

The local server listens on port `8084`; remote debugging listens on port `5006`.

See [Operational guide](doc/operations.md) for production configuration, Telegram login, Moera credentials, database
provisioning, health checks, and opt-in smoke procedures. The canonical design is in
[Architecture](doc/architecture/import-service.md).

After the daemon is running, administer channels, Moera destinations, and mirrors through the host-local CLI:

```bash
./scripts/moera-import-admin --help
```
