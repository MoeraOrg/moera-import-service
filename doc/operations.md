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
one naming environment are not visible in the other; make sure `destinations.node_name` and its credential belong to
the selected environment. Arbitrary private naming-server URLs are not supported.

If the node URL is known but its registered name is not, query it with
[`moctl`](https://moera.org/administration/tools/moctl.html):

```bash
moctl -H https://node.example/moera name show
```

Use the base Moera node URL supplied by that deployment in place of `https://node.example/moera`. The command prints
the value to store as `node_name`. The same name is normally visible as the node's identity in Moera clients.

### Create a dedicated admin token

The importer authenticates as the administrator of the target node because it uploads private media, reads node
features/settings, and creates, updates, and deletes postings and feed stories. Create a separate named token instead
of reusing a client token or storing the administrator password:

```bash
read -rsp 'Moera admin password: ' MOERA_ADMIN_PASSWORD && echo
moctl -N my-node-name token create -n moera-import "$MOERA_ADMIN_PASSWORD"
unset MOERA_ADMIN_PASSWORD
```

Replace `my-node-name` with the registered name and enter the current node administrator password at the prompt.
`token create` authenticates directly with that password and prints the created token information. Because `-p` is
omitted, the token receives all administrator permissions; this covers all current importer operations. The hidden
prompt keeps the password out of shell history; run the command from a trusted administrative host because the CLI must
still pass it to `moctl` as a process argument.

Keep both the token ID and token value long enough to record them separately:

- the token ID is used only for later `moctl token show`, `token update`, or `token delete` operations;
- the token value is the secret required by the importer.

Do not put the token ID, administrator password, root secret, or a `secret:`-prefixed root credential in
`credentialRef`. Admin tokens have the node-configured `token.lifetime`; monitor their expiry and rotate the referenced
secret before it expires.

### Store the token outside PostgreSQL

`destinations.config` stores only a reference to a Moera admin token. The supported forms are:

- `env:VARIABLE_NAME`, resolved from the daemon environment;
- `file:/absolute/path`, read from a mounted secret file.

For an environment-backed secret:

```bash
export MOERA_ADMIN_TOKEN='replace-with-admin-token'
```

For a file-backed secret, make the file readable only by the daemon user:

```bash
sudo chown moera-import:moera-import /run/secrets/moera-admin-token
sudo chmod 0400 /run/secrets/moera-admin-token
```

The database value is then respectively `env:MOERA_ADMIN_TOKEN` or
`file:/run/secrets/moera-admin-token`; the token itself must not be stored in PostgreSQL.

For the file-backed variant, use the following destination configuration in the SQL below:

```sql
'{"credentialRef":"file:/run/secrets/moera-admin-token"}'::jsonb
```

For local development, the environment-backed variant can be placed in `.env.local`:

```dotenv
MOERA_ADMIN_TOKEN=replace-with-the-token-value-not-its-id
```

### Create the destination and mirror

There is no management API in the initial implementation. Provision the target Moera node, Telegram source, and their
mirror in one database transaction. Replace the source URI, registered node name, credential reference, and feed names:

```sql
BEGIN;

WITH new_channel AS (
    INSERT INTO channels (
        source_type,
        source_uri,
        access_state,
        access_retry_at,
        subscription_state,
        subscription_retry_at,
        next_poll_at
    ) VALUES (
        'TELEGRAM',
        'https://t.me/example_channel',
        'UNKNOWN',
        now(),
        'INACTIVE',
        now(),
        now()
    )
    RETURNING id
), new_destination AS (
    INSERT INTO destinations (node_name, config)
    VALUES ('my-node-name', '{"credentialRef":"env:MOERA_ADMIN_TOKEN"}'::jsonb)
    RETURNING id
)
INSERT INTO mirrors (channel_id, destination_id, config)
SELECT new_channel.id, new_destination.id, '{"feeds":["timeline"]}'::jsonb
FROM new_channel, new_destination;

COMMIT;
```

This creates three related objects:

- `channels` tells the Telegram adapter which public channel URL or private invite link to resolve and poll;
- `destinations` tells `MoeraService` which registered node to resolve and where to obtain its admin token;
- `mirrors` connects that source to that destination. Every name in `config.feeds` creates a feed story for the posting;
  `timeline` publishes it to the node's timeline.

One Telegram channel may be connected to several Moera nodes by creating a destination and mirror for each node. A
single destination may likewise receive several Telegram channels through separate mirrors. The unique
`(channel_id, destination_id)` constraint permits only one mirror for a particular pair.

For a private Telegram source, `source_uri` may be a Telegram invite link. Treat it as a secret and do not log or copy
it into diagnostics.

After starting the daemon, use the following query to check resolution, polling, and delivery. `source_id` becomes
non-null after Telegram resolves the source; a successfully delivered publication has equal revisions and a non-null
post ID:

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

If `publication_last_error` reports a missing or unavailable credential, check that `credentialRef` names an existing
environment variable in the daemon process or an absolute file readable by the daemon user. If node resolution fails,
check that `node_name` is registered in the naming service selected by `MOERA_NAMING_SERVER` and is not a URL.

## Starting the daemon

Set the database, Telegram, directory, and Moera-secret environment described above, then start:

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

Add Telegram and secret environment variables to `.env.local` when a real local account is needed. `./run` fixes the
HTTP port at `8084` and enables JDWP on port `5006` without suspending startup.

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

Use a test node and admin token, provision the destination/mirror with the SQL above, and publish a message with an
image and a video in the test Telegram channel. Wait for the pipeline and inspect durable delivery state:

```sql
SELECT revision, published_revision, destination_post_id, destination_data, last_error
FROM publications
ORDER BY id DESC;
```

The smoke succeeds when `destination_post_id` is non-null, `published_revision = revision`, `last_error` is null, and
the posting (including media and requested feed story) is visible on the test Moera node. Edit/pin and then delete the
Telegram message to smoke-test update, story pinning, and deletion.
