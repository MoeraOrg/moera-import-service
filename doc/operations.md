# Operations

## Requirements and build

Install Java 25 and provide a Docker-compatible API for integration tests. The Maven Wrapper downloads and selects the
project's Maven version.

Run all checks, unit tests, and PostgreSQL integration tests:

```bash
./mvnw clean verify
```

Build the executable production JAR without rerunning tests:

```bash
./mvnw -DskipTests package
```

The result is `target/moera-import-0.0.1-SNAPSHOT.jar`.

With rootless Podman, expose its Docker-compatible API in one terminal and point Testcontainers at it in another:

```bash
podman system service --time=0 unix:///tmp/moera-import-podman.sock
```

```bash
DOCKER_HOST=unix:///tmp/moera-import-podman.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
./mvnw clean verify
```

Disabling Ryuk means interrupted test containers may need to be removed manually.

## PostgreSQL

Create a dedicated database and role. Substitute a generated password before running these commands:

```sql
CREATE ROLE moera_import LOGIN PASSWORD 'replace-with-a-generated-password';
CREATE DATABASE moera_import OWNER moera_import;
```

Pass the connection through environment variables:

```bash
export DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/moera_import
export DATABASE_USERNAME=moera_import
export DATABASE_PASSWORD='replace-with-the-generated-password'
```

Flyway validates and applies `src/main/resources/db/migration` automatically during startup. Do not create or modify
application tables manually.

Schema migration `V005` adds typed columns and backfills them while retaining the old JSONB state. Migration `V006`
validates the retained state, repeats the backfill to include valid changes made after `V005`, removes migrated keys
from adapter-owned JSON, and drops the six obsolete JSONB columns. Unknown keys or malformed values stop `V006`
before any legacy column is removed; correct the reported table, row, and field, then restart the application.

Migration `V007` removes the obsolete per-destination `credential_ref` column. It preserves every destination row;
afterward, existing and new destinations use the process-wide carte identity described below.

If the migrations are deployed separately, keep the legacy application version running after `V005`. Deploy the typed
application only together with `V006`; Flyway must complete `V006` before that application starts. Do not run a
typed-only build against a database stopped at `V005`.

## Filesystem state and permissions

Run the daemon as a dedicated operating-system user. The TDLight database is persistent and credential-equivalent;
the cache is disposable, and staging holds temporary media. Only the daemon user should access any of them:

```bash
sudo install -d -o moera-import -g moera-import -m 0700 /var/lib/moera-import/telegram/data
sudo install -d -o moera-import -g moera-import -m 0700 /var/cache/moera-import/telegram
sudo install -d -o moera-import -g moera-import -m 0700 /var/tmp/moera-import/staging
```

Configure the paths consistently for both login and normal startup:

```bash
export TELEGRAM_DATABASE_DIRECTORY=/var/lib/moera-import/telegram/data
export TELEGRAM_DOWNLOAD_CACHE_DIRECTORY=/var/cache/moera-import/telegram
export MOERA_STAGING_DIRECTORY=/var/tmp/moera-import/staging
```

Do not mount the same TDLight database directory into two running JVMs. The initial implementation supports one active
owner of the Telegram session.

## One-time Telegram login

The importer is an MTProto client acting as a Telegram user account. It does not use a bot, and a BotFather token
cannot be used as `TELEGRAM_API_HASH`.

Create Telegram API credentials as follows:

1. Register the account in an official Telegram mobile or desktop application. Use the same active account that will
   read the imported channels.
2. Open [my.telegram.org](https://my.telegram.org), enter the account phone number in international format, and enter
   the confirmation code sent by Telegram.
3. Open **API development tools**.
4. Create an application. Choose a descriptive application title and short name, for example `Moera Import Service`
   and `moeraimport`; fill the remaining form fields with values appropriate for the deployment.
5. Copy **App api_id** into `TELEGRAM_API_ID`. It is a decimal integer.
6. Copy **App api_hash** into `TELEGRAM_API_HASH`. It is a secret string and must not be confused with `api_id`, the
   account password, a login code, or a bot token.

Telegram currently permits only one `api_id` per phone number. Keep the phone number active because Telegram may send
developer notifications to it. See Telegram's official
[Creating your Telegram Application](https://core.telegram.org/api/obtaining_api_id) instructions.

Store both values in deployment secret storage. For local development they may be added to the Git-ignored
`.env.local`; restrict that file to the current user:

```bash
chmod 0600 .env.local
```

```dotenv
TELEGRAM_API_ID=123456
TELEGRAM_API_HASH=0123456789abcdef0123456789abcdef
TELEGRAM_DATABASE_DIRECTORY=/var/lib/moera-import/telegram/data
TELEGRAM_DOWNLOAD_CACHE_DIRECTORY=/var/cache/moera-import/telegram
MOERA_STAGING_DIRECTORY=/var/tmp/moera-import/staging
MOERA_NAMING_SERVER=development
TELEGRAM_ENABLED=true
TELEGRAM_LOGIN=true
```

Stop the normal daemon so it does not have the TDLight database open, then run the built JAR from an interactive
terminal with the database and directory environment configured as described above:

```bash
export TELEGRAM_ENABLED=true
export TELEGRAM_LOGIN=true
java -jar target/moera-import-0.0.1-SNAPSHOT.jar
```

TDLight prompts on the terminal for the phone number, verification code, and 2FA password when applicable. After
authorization completes, the login runner closes TDLight; stop the bootstrap JVM with `Ctrl-C`. Never put the code,
2FA password, or API hash in PostgreSQL or logs.

For every subsequent daemon start, use the same two TDLight directories with login mode disabled:

```bash
export TELEGRAM_ENABLED=true
export TELEGRAM_LOGIN=false
```

## Connecting a Moera node

The target node is selected by its registered Moera name. `destinations.node_name` is not an HTTP URL, hostname, or
display name: it is the exact name registered in the selected Moera naming service. The importer resolves this name on
every operation.

The main naming server is used by default. To use the official development naming server for every destination handled
by this service instance, set:

```dotenv
MOERA_NAMING_SERVER=development
```

The supported values are `main` and `development`. Restart the service after changing the value. Nodes registered in
one naming environment are not visible in the other; make sure the importer identity and every
`destinations.node_name` belong to the selected environment. Arbitrary private naming-server URLs are not supported.

If the node URL is known but its registered name is not, query it with
[`moctl`](https://moera.org/administration/tools/moctl.html):

```bash
moctl -H https://node.example/moera name show
```

Use the base Moera node URL supplied by that deployment in place of `https://node.example/moera`. The command prints
the value to store as `node_name`. The same name is normally visible as the node's identity in Moera clients.

### Configure the importer identity

The service uses one registered Moera identity for every destination. It signs a short-lived, destination-bound carte
for each API request. Configure the identity, its private signing key, and every source IP address from which target
nodes will observe requests:

```dotenv
MOERA_NAMING_SERVER=main
MOERA_AUTHENTICATION_NAME=importer_0
MOERA_AUTHENTICATION_SIGNING_KEY=replace-with-64-hex-character-private-signing-key
MOERA_AUTHENTICATION_ADDRESSES=203.0.113.10,2001:db8::10
```

The full name, including its generation suffix, and the corresponding public signing key must already be registered
in the selected naming environment. `MOERA_AUTHENTICATION_ADDRESSES` is mandatory and may contain comma-separated IPv4
and IPv6 addresses. Use the externally observed source addresses after NAT, not merely addresses assigned to the local
host. All entries are resolved at startup and included in every carte. Restart the service after changing any value.

Store the private key in deployment secret storage. It must not be committed to source control or written to
PostgreSQL or logs. The example value above is deliberately unusable.

### Grant the importer administrative rights

Every destination node must grant the configured importer identity exactly the permissions needed by the service:

- `view-content`
- `add-post`
- `update-post`
- `delete-own-content`
- `view-feeds`
- `update-feeds`
- `view-profile`
- `update-profile`
- `view-settings`
- `upload-public-media`
- `upload-private-media`

Run the following for each destination. Keep the comma-separated scope list in one shell argument:

```bash
IMPORTER_SCOPES='view-content,add-post,update-post,delete-own-content,view-feeds,'\
'update-feeds,view-profile,update-profile,view-settings,upload-public-media,upload-private-media'
moctl -N my-node-name grant grant importer_0 "$IMPORTER_SCOPES"
moctl -N my-node-name grant show importer_0
```

Here `my-node-name` is the destination issuing the grant, and `importer_0` must equal
`MOERA_AUTHENTICATION_NAME`. Repeat the command after changing the importer name or generation.

### Create the destination and mirror

After starting the normal daemon with Telegram enabled and its saved session ready, use the host-local administration
CLI. Replace the source URI and registered destination node name:

```bash
./scripts/moera-import-admin channel add https://t.me/example_channel
./scripts/moera-import-admin destination add my-node-name
./scripts/moera-import-admin channel list
./scripts/moera-import-admin destination list
./scripts/moera-import-admin mirror add 1 1 --feed timeline
./scripts/moera-import-admin mirror list
```

By default, the mirror also keeps the Moera profile synchronized with the Telegram channel. Configure the English
attribution paragraph and its contact address before starting the daemon:

```dotenv
MOERA_PROFILE_CONTACT_EMAIL=balu@moera.org
MOERA_PROFILE_ATTRIBUTION_HTML=<p>This profile mirrors {channelLink}. To import another channel, or your own channel \
into your Moera account, contact {contactLink}.</p>
```

`{channelLink}` becomes a safe link to a public Telegram channel, or unlinked text for a private channel.
`{contactLink}` becomes a `mailto:` link. The Telegram title is used as the Moera full name; the description and the
configured paragraph form the HTML bio. The large Telegram channel photo becomes the global Moera avatar and is
captured in each newly created posting.

Disable profile synchronization when a destination is intended to aggregate several channels:

```bash
./scripts/moera-import-admin destination add aggregate-node --no-profile-sync
```

Without additional options, a newly added Telegram channel imports at most the newest 20 unique messages before
establishing its waterline. To import all available Telegram history for a new channel, opt in explicitly:

```bash
./scripts/moera-import-admin channel add https://t.me/example_channel --full-history
```

`--full-history` affects only the channel's first poll while no waterline exists. Existing messages, publications, and
already delivered Moera postings are never removed when these settings change. A restart preserves the queued backlog
and continues delivering it; new Moera posts are paced by the destination rate described below.

This creates three related objects:

- `channels` tells the Telegram adapter which public channel URL or private invite link to resolve and poll;
- `destinations` tells `MoeraService` which registered node to resolve and holds importer-owned delivery and profile
  state;
- `mirrors` connects that source to that destination. Every name in `mirrors.feeds` creates a feed story for the
  posting; `timeline` publishes it to the node's timeline.

One Telegram channel may be connected to several Moera nodes by creating a destination and mirror for each node. A
profile-synchronized destination accepts one mirror, because that channel owns its profile. A destination created with
`--no-profile-sync` may receive several Telegram channels through separate mirrors. The unique `(channel_id,
destination_id)` constraint permits only one mirror for a particular pair.

For a private Telegram source, `source_uri` may be a Telegram invite link. Treat it as a secret and do not log or copy
it into diagnostics. It will be visible in the local `channel list` output, so run the CLI only in a trusted terminal.

The CLI defaults to `http://127.0.0.1:8084`. A different loopback listener can be selected before the resource command:

```bash
./scripts/moera-import-admin --url http://127.0.0.1:9084 channel list
```

The underlying unauthenticated API rejects peers whose socket address is not loopback. Do not expose `/api` through a
reverse proxy. Binding the whole application to loopback with `SERVER_ADDRESS=127.0.0.1` remains recommended.

The available delete commands are:

```bash
./scripts/moera-import-admin mirror delete 1 1
./scripts/moera-import-admin channel delete 1
./scripts/moera-import-admin destination delete 1
```

They prompt before deletion; use `--yes` only in trusted automation. Deleting a channel or destination cascades through
its importer state. Deleting any of these resources does not contact Moera and does not remove postings that were
already delivered.

For direct integrations, the CLI wraps this JSON API:

```text
GET    /api/channels
POST   /api/channels
DELETE /api/channels/{channelId}
GET    /api/destinations
POST   /api/destinations
DELETE /api/destinations/{destinationId}
GET    /api/mirrors
POST   /api/mirrors
DELETE /api/mirrors/{channelId}/{destinationId}
```

Use the following query for detailed polling and delivery diagnostics. A successfully delivered publication has equal
revisions and a non-null post ID:

```sql
SELECT
    c.id AS channel_id,
    c.source_id,
    c.access_state,
    c.subscription_state,
    c.poll_last_error,
    d.node_name,
    p.revision,
    p.published_revision,
    p.destination_post_id,
    p.last_error AS publication_last_error
FROM mirrors r
JOIN channels c ON c.id = r.channel_id
JOIN destinations d ON d.id = r.destination_id
LEFT JOIN publications p ON p.mirror_id = r.id
ORDER BY r.id, p.id;
```

For authentication failures, check that the importer name and public signing key are registered in the selected naming
environment, the configured private key matches that public key, the destination has the exact Grants above, and the
configured addresses match the source IP observed by the destination. Also verify clock synchronization on the
importer and destination hosts. For resolution failures, confirm that `node_name` is registered in the naming service
selected by `MOERA_NAMING_SERVER` and is not a URL.

## Import flow control

The default flow-control settings and their environment overrides are:

| Setting | Default | Environment override |
| --- | ---: | --- |
| Initial Telegram history | 20 messages | `TELEGRAM_INITIAL_HISTORY_COUNT` |
| Channel workflow concurrency | 2 | `MOERA_CHANNEL_CONCURRENCY` |
| Publication-builder concurrency | 2 | `MOERA_PUBLICATION_BUILDER_CONCURRENCY` |
| Publication-media concurrency | 4 | `MOERA_PUBLICATION_MEDIA_CONCURRENCY` |
| Publication concurrency | 4 | `MOERA_PUBLICATION_CONCURRENCY` |
| Moera creates per period, per destination | 10 | `MOERA_CREATE_RATE_COUNT` |
| Moera create-rate period | 1 minute | `MOERA_CREATE_RATE_PERIOD` |

The default create rate is therefore one new posting every six seconds for each destination. All destinations use the
same configured rate but keep independent durable positions in PostgreSQL, so a busy or failing destination does not
consume another destination's slots and a restart does not reset the rate. The slot is consumed immediately before a
first `createPost` call. Posting updates and deletes, story changes, capability lookup, and media upload are not rate
limited.

Workflow concurrency limits are local to one service process and reserve capacity before database claims. They bound
active channel, grouping, media, and publication work independently; changing one workflow limit does not change the
others. Keep the PostgreSQL lease durations comfortably longer than ordinary work. Long Telegram polls renew their
lease while active and stop completing work if ownership is lost.

## Starting the daemon

Set the database, Telegram, directory, and Moera authentication environment described above, then start:

```bash
export SERVER_PORT=8084
java -jar target/moera-import-0.0.1-SNAPSHOT.jar
```

Flyway runs before workers are enabled. On shutdown, send `SIGTERM` and allow at least the configured
`moera.workers.shutdown-grace-period` (30 seconds by default).

For local development, `.env.local` is ignored by Git. Copy the example once and run the convenience script:

```bash
cp .env.local.example .env.local
./run
```

Add Telegram and authentication environment variables to `.env.local` when a real local account is needed. `./run`
fixes the HTTP port at `8084` and enables JDWP on port `5006` without suspending startup.

## Health and metrics

The Actuator HTTP endpoints are exposed on the application port:

```bash
curl --fail --silent --show-error http://127.0.0.1:8084/actuator/health
curl --fail --silent --show-error http://127.0.0.1:8084/actuator/health/liveness
curl --fail --silent --show-error http://127.0.0.1:8084/actuator/health/readiness
curl --fail --silent --show-error http://127.0.0.1:8084/actuator/prometheus
```

Liveness reports process health. Readiness additionally requires PostgreSQL/schema startup and, when Telegram is
enabled, an initialized saved Telegram session. A failing Moera destination does not make the whole service unready.

Actuator has no application-level authentication in this service. Bind it to a private interface or restrict the
application port at the network boundary; for a host-local deployment, set `SERVER_ADDRESS=127.0.0.1`.

## Opt-in external smoke procedures

The default Maven suite always uses fakes for Telegram and Moera and is safe to run without external accounts. The
repository currently has no automated test that contacts a real account; the following operator-run procedures are
therefore explicitly opt-in.

### Telegram smoke

1. Stop the daemon so only one JVM owns the session directory.
2. Run the one-time login command above against a disposable or designated test account.
3. Restart with `TELEGRAM_LOGIN=false`.
4. Verify readiness:

```bash
curl --fail --silent --show-error http://127.0.0.1:8084/actuator/health/readiness
```

5. Provision a test Telegram channel and confirm that `channels.last_poll_at` and `channels.waterline` become non-null:

```sql
SELECT id, source_id, access_state, subscription_state, last_poll_at, waterline, poll_last_error
FROM channels
ORDER BY id DESC;
```

### Moera smoke

Use a test importer identity with the required Grants, provision the destination and mirror with the CLI above, and
publish a message with an image and a video in the test Telegram channel. Wait for the pipeline and inspect durable
delivery state:

```sql
SELECT
    p.revision,
    p.published_revision,
    p.destination_post_id,
    p.destination_pinned,
    s.feed_name,
    s.story_id,
    pm.destination_media_id,
    pm.destination_hash,
    pm.compression_pending,
    p.last_error
FROM publications p
LEFT JOIN publication_stories s ON s.publication_id = p.id
LEFT JOIN publication_media pm ON pm.publication_id = p.id
ORDER BY p.id DESC, s.feed_name, pm.media_id;
```

The smoke succeeds when `destination_post_id` is non-null, `published_revision = revision`, `last_error` is null, and
the posting (including media and requested feed story) is visible on the test Moera node. Edit/pin and then delete the
Telegram message to smoke-test update, story pinning, and deletion.
