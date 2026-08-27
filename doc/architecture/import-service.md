# Moera Import Service Architecture

**Status:** Living design document  
**Date:** 2026-08-27

## 1. Purpose

The service imports channels/feeds from external networks and mirrors their content to Moera. Telegram is the first
source integration. RSS is intended to be the next source integration, with Mastodon and other sources possible later.

The architecture keeps source-specific behavior behind adapters and uses a universal core model. This document is the
canonical source of truth for agreed architectural decisions. Chat discussions are design history; once a decision is
recorded here, this document is authoritative.

## 2. Technology stack

- Java 25.
- Spring.
- PostgreSQL.
- Flyway as the **only source of truth** for the database schema.
- jOOQ generated from the Flyway-defined schema.
- TDLight for Telegram.
- Moera Java library for Moera integration.

jOOQ is confined to the persistence layer. SQL/jOOQ DSL is not scattered through application code.

Repositories are concrete low-level database classes such as `MirrorRepository`. There is no repository interface plus
a separate jOOQ implementation because alternative persistence implementations are not expected.

## 3. Architectural overview

```text
SourceAdapter
     |
     v
  Channel
     |
     v
  Message ---- Media
     |
     | grouping
     v
 Publication
     |
     v
MoeraService
     |
     v
 Destination (Moera node/account)
```

A `Channel` is a channel/feed in an external source. A `Message` is one item observed in that channel. One or more
consecutive messages may form one `Publication`. A `Mirror` connects a channel to a Moera destination. A `Publication`
is the delivery object for one immutable group of source messages through one mirror.

Source-specific properties that do not belong to the universal model are stored in JSONB fields.

## 4. Database naming and schema ownership

Database tables use plural names:

```text
channels
messages
message_link_previews
media
destinations
mirrors
publications
publication_messages
publication_media
```

`media` is already a plural noun and is not renamed to `medias`.

The initial schema is defined by:

```text
src/main/resources/db/migration/V001__initial_schema.sql
```

Flyway SQL is authoritative. Documentation summarizes the model but does not supersede the migration.

String fields use `varchar(n)` where a meaningful upper bound exists; unbounded content/diagnostics use `text`.

## 5. Source types

`source_type` is a PostgreSQL enum. The initial migration contains only:

```text
TELEGRAM
```

Future values such as `RSS` are added only when the corresponding adapter is actually implemented, via a Flyway
migration.

## 6. Channel

`Channel` represents an external channel/feed, not a mirroring rule.

Persistent data includes:

```text
id
source_type
source_id
source_uri
source_data JSONB

access_state
access_attempts
access_retry_at
access_last_error
access_data JSONB

subscription_state
subscription_attempts
subscription_retry_at
subscription_last_error
subscription_data JSONB

waterline JSONB

last_poll_at
next_poll_at
poll_attempts
poll_last_error

processing_operation
processing_token
processing_until

created_at
updated_at
```

`source_id` is an opaque stable identifier understood by the corresponding `SourceAdapter`. It may be `NULL`
temporarily when the external source cannot reveal stable identity before access is granted. Once resolved, it is
immutable. `source_uri` is a usable/reference link to the source and is always available. For Telegram the stable ID is
the chat ID while the URI may be a public username link or private invite link.

A `(source_type, source_id)` pair is unique for non-null IDs. PostgreSQL naturally allows multiple unresolved rows with
`source_id = NULL`.

A channel owns many messages and may have many mirrors.

## 7. Access lifecycle

Access permission is distinct from subscription to update events.

Universal states:

```text
UNKNOWN
GRANTED
REQUESTED
DENIED
```

Meaning:

- `UNKNOWN`: access has not yet been established or checked.
- `GRANTED`: the adapter can read channel contents.
- `REQUESTED`: an access request was submitted and is awaiting a decision.
- `DENIED`: access is unavailable, refused, or revoked.

Source-specific details stay in `access_data`.

`access_attempts`, `access_retry_at`, and `access_last_error` are universal scheduler/diagnostic state.

## 8. Subscription lifecycle

Subscription means subscription to **update events**, not permission to read channel contents.

Universal states:

```text
UNSUPPORTED
INACTIVE
ACTIVATING
ACTIVE
FAILED
LOST
```

Meaning:

- `UNSUPPORTED`: the source does not support event subscription.
- `INACTIVE`: subscription is supported but not active.
- `ACTIVATING`: activation is in progress.
- `ACTIVE`: live update events are expected.
- `FAILED`: activation failed and will be retried later.
- `LOST`: a previously active live-update state was lost unexpectedly.

`LOST` is universal even when a source does not expose a literal server-side subscription object. For Telegram it can
represent loss of membership/access assumptions or another condition requiring live state to be established again.

Source-specific details stay in `subscription_data`.

## 9. Polling

Polling exists even when live events are active. It provides bounded backfill/checking and is used more frequently when
event subscription is unavailable.

The polling interval is selected by `SourceAdapter`, not by the core or `Mirror`.

Typical behavior:

```text
access GRANTED + subscription ACTIVE
    -> infrequent polling

access GRANTED + subscription unavailable/inactive/failed/lost
    -> more frequent polling

access not GRANTED
    -> contents cannot be polled
```

Persistent scheduling state is:

```text
last_poll_at
next_poll_at
poll_attempts
poll_last_error
```

A successful poll calculates the next regular poll using the source adapter's policy.

## 10. Waterline

`waterline` is opaque JSON interpreted exclusively by the corresponding `SourceAdapter`.

Its semantic meaning is:

> the position up to which the adapter has successfully completed a forward scan and the service has persisted all
> messages discovered in that scan.

It is not defined as the newest known message ID or as a timestamp.

Conceptually:

```java
public record PollResult(
    List<SourceMessage> messages,
    JsonNode waterline
) {}
```

The core persists the batch first. Only after successful persistence does it advance the channel waterline. If
processing fails, the previous waterline remains and a later poll may repeat data safely.

### 10.1 Look-behind

Polling also reads a source-specific overlap before the current waterline to detect recent edits/deletions. The overlap
may be expressed by count, time, or another source-specific mechanism.

Look-behind does not move the waterline backwards. No full historical reconciliation is performed, so sufficiently old
edits may be missed; this is accepted.

Absence from a poll result is not automatically interpreted as deletion. Deletion is recorded only when the source
adapter can establish it reliably.

### 10.2 Initial scan

`waterline = NULL` means that no successful initial forward scan has completed. The source adapter chooses the initial
import scope.

## 11. Message

A `Message` belongs to exactly one `Channel`.

A repost/forward is still a separate message in each channel where it appears. Forward/origin metadata may be stored in
`source_data`; it does not create identity links between messages.

External identity is unique inside the channel:

```text
UNIQUE (channel_id, source_id)
```

Persistent data:

```text
id
channel_id
source_id
revision
deleted
pinned
published_at
body
body_format
source_body_hash
source_data JSONB
created_at
updated_at
```

Only the latest revision is stored. Revision history is not archived.

`revision` is a service-owned `integer` starting at `0`. It increases whenever the normalized source state changes,
including text/formatting, link previews, media set/content, pinning, or deletion state.

`created_at` is the time the service first stored the message. `published_at` is the source publication time used for
grouping. A separate `observed_at` is unnecessary.

## 12. Message body

The service stores text already converted into a destination-friendly universal representation:

```text
body
body_format
```

Supported formats:

```text
PLAIN_TEXT
MARKDOWN
HTML
```

`body`, `body_format`, and `source_body_hash` are always non-null. A message with no text uses:

```text
body = ''
body_format = PLAIN_TEXT
```

`source_body_hash` is SHA-256 (32 bytes) over a canonical source representation that includes formatting semantics, not
only visible characters. Thus a formatting-only edit is detected.

The source adapter defines the canonical source representation; the project standardizes the hash algorithm/size.

The canonical body representation also includes normalized structured values that affect the destination body, such as
link-preview metadata, forward attribution, author signatures, supported buttons, and fact-checks. Source fields that
the mapping rules explicitly ignore are not included.

When messages with different body formats are combined into a publication, the publication-building/destination path
converts them to a common representation acceptable to Moera.

### 12.1 Link previews

Link previews are universal structured body data rather than Telegram JSON or pre-encoded Moera `Body` values.
`SourceMessage` contains ordered `SourceLinkPreview` values with:

```text
site_name
url
title
description
published_at
image_source_media_id
```

They are persisted in `message_link_previews` with the owning `message_id`, a source order `position`, the fields above,
and an optional `image_media_id` foreign key to `media`. The referenced image is also an ordinary `Media` row and can be
attached to the destination publication. This keeps the core model source-neutral and lets the Moera payload builder
construct `Body.linkPreviews` without parsing `source_data`.

When several member messages contribute previews with the same URL, the publication uses the first preview in message
order and ignores later duplicates, including their preview-only images. The effective preview/media selection is
recalculated when a member message changes, so a later duplicate can become active if the earlier one disappears.

## 13. Media

`Media` rows are created during source ingest together with their owning `Message`. They describe source files; they do
not mean the file has already been downloaded.

Persistent data:

```text
id
message_id
source_id
position
mime_type
file_name
size
source_hash
source_data JSONB
created_at
updated_at
```

No universal `media_type`, dimensions, or duration are stored because the core does not need them. Moera determines
media properties itself.

`mime_type` defaults to `application/octet-stream` when the source cannot provide a better value.

`position` preserves source attachment order within the message.

A media item referenced as a link-preview image still participates in ordinary publication-media upload and attachment.
The link-preview relation determines whether it is also referenced from `Body.linkPreviews` and whether a duplicate
preview makes it ineffective for a particular publication.

`source_hash` is a non-null SHA-256 fingerprint of the canonical source media representation. If `(source_id,
source_hash)` is unchanged, the media is unchanged. A changed hash, addition, or removal of media increments the owning
message revision.

The original media file is not stored permanently.

## 14. SourceAdapter boundary

`SourceAdapter` is the boundary between the universal core and an external source.

The core decides **when** an operation should run and persists durable state. The adapter knows **how** to perform that
operation for its source.

A source adapter:

- does not write to PostgreSQL directly;
- does not create publications;
- does not know about Moera;
- does not own durable retry counters/timestamps;
- normalizes source messages/media into the universal model;
- downloads source media on demand into a core-owned staging path;
- interprets `source_data`, `access_data`, `subscription_data`, and `waterline`.

Conceptually:

```java
public interface SourceAdapter {
    SourceType type();

    ChannelResolution resolveChannel(URI sourceUri);

    Duration groupingThreshold();
    Duration settlingDelay();
    Duration pollingInterval(ChannelSnapshot channel);

    AccessResult refreshAccess(ChannelSnapshot channel);
    SubscriptionResult refreshSubscription(ChannelSnapshot channel);
    PollResult poll(ChannelSnapshot channel);

    void downloadMedia(
        ChannelSnapshot channel,
        MediaSnapshot media,
        Path destination
    );

    void start(SourceEventSink sink);
    void stop();
}
```

Network operations may be synchronous at the core boundary and run on Java virtual threads. TDLight callbacks stay
inside the Telegram adapter.

The core owns temporary-file creation and cleanup. `downloadMedia(...)` only materializes the requested source file at
the supplied path; the adapter does not own staging lifecycle.

`resolveChannel(...)` turns a user-supplied source URI into the best available external identity and initial source
state. The result may contain a null `sourceId` when an external service deliberately withholds stable identity until
access is granted. `AccessResult` may later return a `resolvedSourceId`; the core persists it only if the channel has
no source ID yet, and treats a conflicting later ID as a permanent consistency error.

## 15. Live source events

Adapters that support live updates emit normalized events through a core-owned sink.

Conceptually:

```java
public interface SourceEventSink {
    void accept(SourceEvent event);
}
```

Events may include:

```text
MessageChanged
MessageDeleted
AccessLost
SubscriptionLost
```

Polling and live events feed the same message-ingest logic.

An adapter identifies a channel by external `(source_type, source_id)`, not by internal database ID.

## 16. Message change detection

For an observed source message:

1. Normalize body, formatting, structured link previews, and pinning.
2. Compute `source_body_hash`.
3. Normalize the source media set and each `source_hash`.
4. Compare with stored state.
5. If unchanged, keep the existing revision.
6. If body, link previews, media, pinning, or deletion state changed, update the stored state and increment `revision`.

Deletion is represented by another revision with `deleted = true`.

Source-native revision IDs/timestamps may remain in `source_data`, but do not define the service-owned revision counter.

## 17. Destination

There is no planned destination type other than Moera, so `destinations` has no destination-type discriminator.

A destination identifies a concrete Moera node/account:

```text
id
node_name varchar(135)
config JSONB
data JSONB
created_at
updated_at
```

`node_name` is unique.

`config` is service-owned configuration. `data` is persistent Moera-adapter state. Secrets should preferably be
referenced rather than embedded directly in JSONB.

## 18. Mirror

A `Mirror` connects one `Channel` to one Moera `Destination`:

```text
id
channel_id
destination_id
enabled
config JSONB
created_at
updated_at
```

A `(channel_id, destination_id)` pair is unique.

Grouping rules do not belong to `Mirror`; they are source semantics and belong to `SourceAdapter`.

## 19. Grouping messages into publications

A `Publication` is not created immediately after ingest. The service waits so that consecutive source messages can be
grouped.

`SourceAdapter` owns:

```text
groupingThreshold
settlingDelay
```

- `groupingThreshold`: maximum gap between consecutive messages that may form one publication.
- `settlingDelay`: live quiet period after the newest message before the group is considered complete.

Grouping compares consecutive source publication timestamps.

Example with 30-second threshold:

```text
A 10:00:00
B 10:00:20
C 10:00:35
D 10:03:00

Publication 1 = A + B + C
Publication 2 = D
```

This also handles Telegram albums and long Telegram texts split into several messages.

For historical/polled batches, a visible later time gap may prove that a group has ended without waiting for live
settling delay.

### 19.1 Source-specific grouping hints

Timing is the fallback. A source adapter may also provide:

```text
MUST_GROUP
MUST_SEPARATE
UNKNOWN
```

For example, equal Telegram media-group IDs can yield `MUST_GROUP`. Such source metadata stays source-specific and is
not promoted to universal database columns.

### 19.2 Immutable membership

Once a `Publication` is created, its `Message` membership and order are immutable. There is no late
regrouping/reconciliation.

A late message forms part of a new publication even if hindsight suggests it could have belonged to an old one.

Edits/deletions of existing members change the publication revision but not membership.

## 20. Publication

Persistent data:

```text
id
mirror_id
revision
published_revision
destination_post_id
destination_data JSONB
attempts
next_attempt_at
last_error
processing_token
processing_until
created_at
updated_at
```

`revision` is an `integer` starting at `0`.

`published_revision` is null until some revision has been delivered successfully.

Examples:

```text
revision = 4, published_revision = 4
    destination is current

revision = 5, published_revision = 4
    update is needed

revision = 0, published_revision = NULL
    no revision has yet been delivered
```

A worker captures the target revision before external work. If the publication changes while the worker is running, a
successful worker records only its captured target revision; the newer revision remains pending.

## 21. PublicationMessage

The immutable membership relation is stored in `publication_messages`:

```text
publication_id
message_id
position
```

The database enforces only direct referential integrity and ordering uniqueness. More complex domain invariants are
enforced in Java rather than by denormalized composite foreign keys.

`position` fixes message order within the publication.

## 22. PublicationMedia

`publication_media` stores destination-specific delivery state for source media:

```text
publication_id
media_id
status
skip_reason
destination_media_id varchar(40)
destination_data JSONB
attempts
next_attempt_at
last_error
processing_token
processing_until
created_at
updated_at
```

Statuses:

```text
PENDING
UPLOADED
SKIPPED
```

Skip reasons:

```text
TOO_LARGE
UNSUPPORTED
SOURCE_UNAVAILABLE
```

A skipped item is a normal terminal result and does not block the rest of the publication.

There is no extra global media position here. Ordering is derived from `publication_messages.position` followed by
`media.position`.

The database does not attempt to prove that a referenced `Media` belongs to one of the publication's messages;
application code constructs these rows correctly.

For an `UPLOADED` row, `destination_media_id` stores the final attachable `PrivateMediaFileInfo.id` and
`destination_data` stores its `hash`. The ID attaches the private file to a posting; the hash is used by
`Body.LinkPreview.imageHash` when the media is a selected link-preview image.

A `PENDING` row may already contain the original uploaded media ID/hash while Moera is compressing it. In that case,
`destination_data` also marks compression as pending. Once compression finishes, the original ID/hash are replaced by
the compressed file's ID/hash and the row becomes `UPLOADED`. A source-hash change clears both the intermediate and
final destination data.

## 23. Media staging and upload

The source file is downloaded only when a publication needs it.

```text
source Media metadata
        |
        v
check Moera capabilities
        |
        +-- known size too large -> SKIPPED / TOO_LARGE
        |
        v
download to temporary staging
        |
        v
verify real size if necessary
        |
        +-- too large -> SKIPPED -> delete staging
        |
        v
upload to Moera
        |
        +-- temporary failure -> keep staging for retry
        +-- terminal skip/failure -> delete staging
        +-- compression pending -> persist original ID/hash as PENDING
        |                          -> delete staging
        |                          -> poll Moera without re-uploading
        v
final attachable media -> UPLOADED -> delete staging immediately
```

The first implementation does not share staging files across mirrors. Duplicate downloads are acceptable in exchange
for a simple local lifecycle.

Startup/periodic cleanup removes orphan staging files.

### 23.1 Moera-specific behavior

`MoeraService` obtains destination capabilities from the concrete Moera node's `/features` and node-settings
endpoints. The capabilities relevant to importing are primarily:

```text
media.max-size from getNodeSettings("media.max-size")
posting.sourceFormats from /features
```

Capabilities may be cached in memory for a short TTL, but PostgreSQL is not their source of truth.

For each source media item:

1. If an exact source size is already known and exceeds `mediaMaxSize`, mark it `SKIPPED / TOO_LARGE` without
   downloading it.
2. Otherwise download it to core-owned staging.
3. Check the actual file size before upload.
4. If it is too large, mark it `SKIPPED / TOO_LARGE` and delete the staging file.
5. Upload acceptable files as Moera private media.
6. Always request `downsize=true` for Moera media uploads.
7. If the returned file does not require asynchronous compression, store its `PrivateMediaFileInfo.id` in
   `publication_media.destination_media_id` and its `hash` in `publication_media.destination_data`, then mark the row
   `UPLOADED`.
8. If `PrivateMediaFileInfo.uncompressed == true`, persist the original ID/hash with a compression-pending marker,
   leave the row `PENDING`, and poll the original file's private-media information until `compressedMediaId` appears.
9. Fetch `PrivateMediaFileInfo` for `compressedMediaId`, replace the stored destination ID/hash with the compressed
   file's values, and only then mark the row `UPLOADED`.
10. Delete staging immediately after the upload has succeeded, after a terminal skip, or after a terminal failure.

A Moera `media.wrong-size` response is a terminal `TOO_LARGE` outcome. A storage/service failure is retryable when the
Moera error semantics indicate a temporary condition.

`downsize=true` is an internal `MoeraService` rule, not a universal media property.

### 23.2 Asynchronous Moera media compression

Compression waiting uses `publication_media` as its durable queue; it does not introduce a generic job table and does
not keep a worker, PostgreSQL transaction, or lease occupied while compression runs.

After the initial upload reports `uncompressed=true`, conditional completion stores the original ID/hash, leaves
`status=PENDING`, clears the processing lease, keeps `last_error` empty, and sets `next_attempt_at` using a fixed,
configurable compression-poll interval. Claiming this row still increments `attempts` according to the common queue
rule, but waiting for compression is not classified as a failed attempt and does not use exponential failure backoff.

On a later claim, the processor recognizes the compression-pending marker and calls Moera
`getPrivateMediaInfo(originalId, null)` instead of downloading or uploading the source again. A missing
`compressedMediaId` schedules another normal compression check. Once it is present, the processor fetches the
compressed file's information and conditionally completes the row as `UPLOADED` with the compressed ID/hash. Both the
intermediate wait transition and final completion must match `processing_token` and the expected source hash, so a
stale compression result cannot overwrite newer source media.

Because the payload builder reads only `UPLOADED` rows, `MediaToAttach` and any applicable link-preview `imageHash`
automatically use the final compressed file information. No body-text hash replacement is needed specifically for
Telegram video: video is never selected as a link-preview image.

If a source media fingerprint changes while an old upload is running, the corresponding `publication_media` state is
reset to `PENDING` and its lease token is invalidated. The old worker's eventual result is discarded because its token
no longer matches.

## 24. MoeraService boundary

No destination other than Moera is planned, so there is no destination abstraction/interface. A concrete `MoeraService`
owns Moera-specific capabilities, node resolution, authentication, media upload, and posting CRUD. It knows nothing
about channel polling, waterlines, or grouping.

Conceptually:

```java
public class MoeraService {
    DestinationCapabilities capabilities(Destination destination);

    MediaUpload uploadMedia(
        Destination destination,
        Path file,
        Media media
    );

    MediaUpload checkMediaCompression(
        Destination destination,
        String originalMediaId
    );

    CreatedPosting createPost(
        Destination destination,
        PublicationPayload publication
    );

    void updatePost(
        Destination destination,
        String destinationPostId,
        PublicationPayload publication
    );

    void deletePost(
        Destination destination,
        String destinationPostId
    );

    void updateStory(
        Destination destination,
        String storyId,
        boolean pinned
    );
}

public sealed interface MediaUpload {
    record Ready(UploadedMedia media) implements MediaUpload;
    record AwaitingCompression(UploadedMedia original) implements MediaUpload;
}
```

`MoeraClientFactory` resolves the current node URL from `destination.node_name`, obtains the credential referenced by
`destinations.config`, and creates an authenticated Moera client. A global service setting selects either the main or
development Moera naming server; the main server is the default. All destinations handled by one service instance use
the same naming server. Node URL resolution must not be treated as permanently stable.

Authentication, Moera IDs, `downsize=true`, API flags, and future idempotency state remain inside `MoeraService` and
destination/publication JSONB state.

### 24.1 Publication text format

Messages keep whichever normalized source representation was easiest to produce safely: `PLAIN_TEXT`, `MARKDOWN`, or
sanitized `HTML`.

When a `Publication` is materialized for Moera, the processor chooses one source format supported by the destination
node:

```text
all non-empty messages have the same format
and the node supports it
    -> preserve that format

otherwise, if HTML is supported
    -> convert all message bodies to HTML

otherwise
    -> fall back to PLAIN_TEXT
```

The design does not require HTML-to-Markdown conversion. Telegram normally produces HTML, and RSS is also expected to
commonly produce HTML.

The payload supplies a structured Moera `Body` as `bodySrc`: its `text` field contains the selected source text, and its
`linkPreviews` field contains the normalized universal previews. `bodySrcFormat` describes the text field. The Moera node
builds its processed representation.

A publication consisting only of media may have an empty source body.

### 24.2 Joining multiple message bodies

A `Publication` can contain several source messages. Their non-empty bodies are joined in
`publication_messages.position` order using a paragraph boundary between source messages.

For plain text or Markdown this is conceptually a blank line. For HTML it is an appropriate HTML-level boundary rather
than raw `\n\n` inserted into markup.

Telegram forward attribution is the one source-specific addition to this rule. Attribution is part of each normalized
message body and is repeated before every forwarded message. When a Telegram publication joins a message whose
attribution differs from the previous message's attribution, the payload builder inserts `<hr>` before that message.

Ordered universal link previews are collected from member messages after deleted messages are removed. They are
deduplicated by URL; the first preview and its image win. The resulting values populate Moera `Body.linkPreviews`.

### 24.3 Publication time and feeds

The Moera posting object's creation timestamp is not used to reproduce the original source timestamp. The physical
posting is created at the current time.

Instead, when the post is initially published to Moera feeds, its feed publication time is derived from the earliest
member message:

```text
publication source time = min(member Message.published_at)
```

This value is used as the publication/story `publishAt` value so imported history appears in source chronological order
even when imported later.

The target feed set belongs to `mirrors.config`, for example conceptually:

```json
{
  "feeds": ["timeline"]
}
```

Feed publication membership and publication time are set when the Moera posting is first created. They are not rebuilt
on ordinary content updates.

Pinning is an exception. A publication is pinned when any non-deleted member message is pinned. Creation passes this
value in `StoryAttributes.pinned`. The create response's `FeedReference.storyId` values are stored by feed in
`publications.destination_data`. Later pin/unpin changes use Moera's separate `updateStory(...)` operation because
`PostingText.publications` cannot be modified when a posting is updated.

## 25. Publication delivery

A publication whose `revision` differs from `published_revision` is not current.

Delivery flow:

1. Resolve current Moera destination capabilities.
2. Process `PENDING` publication media, including polling already-uploaded files that await Moera compression.
3. Wait until all current media are `UPLOADED` or `SKIPPED`. A `PENDING` item with `next_attempt_at = NULL` blocks
   publication until an input/configuration change re-arms it.
4. Build the current payload from member messages in fixed publication order and from uploaded media.
5. Select a source body format supported by the Moera node.
6. On first creation, include the configured feed publications and use the earliest member `published_at` as feed
   `publishAt`.
7. Create a Moera posting if no destination post exists, recording the posting ID and feed story IDs.
8. Update an existing posting if the current payload is non-empty.
9. Update each existing feed story if its effective pin state changed.
10. Delete an existing posting if all text and media content disappeared.
11. On success, set `published_revision` to the exact target revision captured by the worker.

Deleted member messages contribute no content. Skipped media is omitted.

For create, the returned Moera posting ID is stored in `publications.destination_post_id`; returned feed/story identity
and delivered pin state are stored in `publications.destination_data`.

For update, posting content/media and the pin attribute of existing feed stories may be updated; feed membership/time
established at create time are left unchanged. Story updates are repeatable by story ID. A publication revision is
completed only after all required posting and story operations succeed.

For delete, a Moera "posting not found" result is treated as the desired final state and therefore as success.

## 26. Moera create idempotency limitation

Moera currently does not provide create-post idempotency.

Ambiguous failure is therefore unavoidable:

```text
create request reaches Moera
Moera creates the post
response is lost / client times out
```

The service cannot know whether creation happened. The initial behavior is to retry, accepting a small risk of
duplicate posts because silent loss is worse.

An unknown create outcome is logged/diagnosed distinctly.

Future Moera idempotency is an internal `MoeraService` concern. The core does not expose an idempotency capability/key
abstraction. Persistent Moera-specific tokens can be stored in `publication.destination_data`.

The importer does not use `createRemotePosting(...)` or `updateRemotePosting(...)` to work around media compression.
Those calls are asynchronous and do not return the created posting ID or a trackable result containing it, which would
break durable create completion and make ambiguous outcomes harder to reconcile. The importer waits for final media as
described above, then uses synchronous `createPosting(...)` / `updatePosting(...)`, preserving the posting and feed
story IDs returned by Moera.

## 27. Leasing and concurrency

No separate jobs table is used. `channels`, `publication_media`, and `publications` themselves are work queues.

The common pattern is:

```text
PostgreSQL
    |
    | SELECT ... FOR UPDATE SKIP LOCKED
    v
short claim transaction
    |
    | assign processing_token + processing_until
    | increment attempts where applicable
    v
COMMIT
    |
    v
external operation on a virtual thread
    |
    v
conditional completion
WHERE processing_token = :token
```

No network call is performed while holding a database transaction open.

### 27.1 Lease token

A lease has:

```text
processing_token UUID
processing_until timestamptz
```

A completion update succeeds only if the token still matches. If a worker is delayed beyond its lease and another
worker claims the row, the old worker cannot overwrite the newer result.

The first implementation does not use heartbeat/lease renewal. External operations have hard timeouts and leases are
configured with sufficient margin. Renewal can be added later if long uploads require it.

No `processing_owner` is stored; worker/instance identity belongs in structured logs together with the token.

### 27.2 Channel serialization

A channel has one lease shared by scheduled operations and additionally records:

```text
processing_operation = ACCESS | SUBSCRIPTION | POLL
```

Only one scheduled channel operation runs at a time. Logical priority is:

```text
ACCESS
SUBSCRIPTION
POLL
```

subject to which operation is currently due/valid.

## 28. Retry semantics

`attempts` means the number of attempts **started** for the current work since the last success or input change. It is
incremented at claim time, not after failure. Thus a worker crash is still counted.

On success:

```text
attempts = 0
next_attempt_at = NULL
last_error = NULL
```

For regularly recurring polling, success instead schedules the next normal poll through
`SourceAdapter.pollingInterval(...)` while resetting the failure count/error.

### 28.1 Error categories

Adapter boundaries distinguish conceptually:

```text
TemporaryException
PermanentException
OutcomeUnknownException
```

- `TemporaryException`: retryable infrastructure/network/rate-limit failure.
- `PermanentException`: repeating the same operation with unchanged input is pointless.
- `OutcomeUnknownException`: a mutating request may or may not have succeeded; currently important for Moera create.

Expected external states such as access denial, pending access request, subscription loss, or media too large are
domain results, not generic exceptions.

### 28.2 Retry scheduling

Core uses exponential backoff with jitter, configured globally, conceptually:

```text
delay = min(base * 2^(attempts - 1), max)
```

If the external service supplies a `retryAfter`, effective delay is not shorter than it.

A transient/unknown-outcome failure clears the lease and sets:

```text
next_attempt_at = now + retry delay
last_error = concise diagnostic text
```

A permanent failure clears the lease and leaves:

```text
next_attempt_at = NULL
last_error != NULL
```

Therefore `next_attempt_at = NULL` consistently means that the scheduler must not automatically run the item now.

New work is armed with `next_attempt_at = now()`.

### 28.3 Input changes re-arm work

When the input changes, retry state is reset:

```text
attempts = 0
last_error = NULL
next_attempt_at = now()
```

Examples include a new publication revision, changed source media fingerprint, or relevant destination configuration
change.

For changed source media, the active media lease is invalidated so an obsolete upload result cannot be accepted.

### 28.4 Media terminal results

These are normal outcomes rather than retry errors:

```text
SKIPPED / TOO_LARGE
SKIPPED / UNSUPPORTED
SKIPPED / SOURCE_UNAVAILABLE
```

They allow the publication to proceed without that file.

## 29. Persistence constraints and indexes

The database guarantees straightforward structural integrity:

- primary keys;
- direct foreign keys;
- obvious uniqueness constraints;
- non-null requirements;
- simple checks such as non-negative counters/positions and 32-byte hashes.

Complex domain invariants are generally enforced in Java rather than through denormalized composite foreign keys.

Important indexes include:

- `(channel_id, published_at, id)` on `messages` for grouping;
- due-time indexes for access/subscription/poll scheduling;
- `publication_messages(message_id)` to find affected publications when a message changes;
- `publication_media(media_id)` to find delivery state when media changes;
- due-work indexes for `publications` and `publication_media`;
- `mirrors(destination_id)` and `publications(mirror_id)` for reverse lookups.

## 30. Persistence architecture

```text
Flyway SQL migrations
        |
        v
PostgreSQL schema
        |
        v
jOOQ code generation
        |
        v
concrete repository classes
        |
        v
domain/application code
```

Generated jOOQ tables/records and `DSLContext` stay inside persistence code. Repositories return normal Java domain
values/records rather than jOOQ records.

Schema changes are made in Flyway first and jOOQ is regenerated afterwards.

## 31. Java code structure

The implementation uses three main kinds of classes with explicit boundaries:

```text
SourceAdapter / MoeraService
    talk to external systems

Repository
    talks to PostgreSQL through jOOQ

Application service / Processor
    implements workflows and coordinates the two
```

A proposed package layout is:

```text
<base-package>/
├── model/
│   ├── Channel.java
│   ├── Message.java
│   ├── Media.java
│   ├── Destination.java
│   ├── Mirror.java
│   ├── Publication.java
│   ├── PublicationMessage.java
│   ├── PublicationMedia.java
│   └── ... enums
│
├── source/
│   ├── SourceAdapter.java
│   ├── SourceAdapterRegistry.java
│   ├── ChannelSnapshot.java
│   ├── MediaSnapshot.java
│   ├── SourceMessage.java
│   ├── SourceLinkPreview.java
│   ├── SourceMedia.java
│   ├── PollResult.java
│   ├── AccessResult.java
│   ├── SubscriptionResult.java
│   ├── SourceEvent.java
│   ├── SourceEventSink.java
│   └── ... source exceptions
│
├── source/telegram/
│   ├── TelegramSourceAdapter.java
│   ├── TelegramClient.java
│   ├── TelegramMessageMapper.java
│   ├── TelegramMappingResult.java
│   ├── TelegramHtmlFormatter.java
│   ├── TelegramCanonicalHash.java
│   └── TelegramMediaDownloader.java
│
├── moera/
│   ├── MoeraService.java
│   ├── MoeraClientFactory.java
│   └── MoeraPublicationMapper.java
│
├── persistence/
│   ├── ChannelRepository.java
│   ├── MessageRepository.java
│   ├── DestinationRepository.java
│   ├── MirrorRepository.java
│   ├── PublicationRepository.java
│   └── PublicationMediaRepository.java
│
├── application/
│   ├── ChannelProcessor.java
│   ├── SourceEventProcessor.java
│   ├── PublicationBuilder.java
│   ├── PublicationMediaProcessor.java
│   └── PublicationProcessor.java
│
├── scheduling/
│   ├── ChannelScheduler.java
│   ├── PublicationBuilderScheduler.java
│   ├── PublicationMediaScheduler.java
│   └── PublicationScheduler.java
│
└── config/
```

Domain/database-facing values are simple immutable Java records where practical. They are not jOOQ records and are not
active-record entities.

Repositories are concrete classes and may implement atomic persistence operations rather than only primitive CRUD. For
example, `MessageRepository.ingest(...)` may, in one transaction, compare hashes/media, insert or update the message,
synchronize its media rows, and increment the message revision when necessary.

Schedulers are deliberately thin. They ask repositories to claim due work and hand claimed items to processors,
normally running network/file work on Java virtual threads. `FOR UPDATE SKIP LOCKED`, lease creation, and conditional
lease completion remain encapsulated in repositories.

`SourceAdapterRegistry` maps `SourceType` to the corresponding Spring-managed `SourceAdapter`, so application code does
not switch on Telegram/RSS/Mastodon explicitly.

## 32. Configuration, secrets, and local state

The service separates four kinds of state:

```text
ordinary static configuration        -> Spring configuration
secrets                              -> environment or mounted secret files
durable application state           -> PostgreSQL
Telegram TDLight authorization state -> persistent filesystem volume
```

Operational tuning such as worker batch sizes, lease durations, retry backoff, Telegram polling intervals, grouping
threshold, settling delay, and look-behind size belongs in typed Spring `@ConfigurationProperties`, not in PostgreSQL.

### 32.1 TDLight session storage

The first implementation uses one Telegram user account and one TDLight client for all Telegram channels.

TDLight's database/session directory is persistent and survives service restarts/redeployments. A conceptual filesystem
layout is:

```text
/var/lib/moera-import/telegram/data/   persistent TDLight database/session
/var/cache/moera-import/telegram/      disposable TDLight download cache
/var/tmp/moera-import/staging/         core-owned temporary publication media
```

The TDLight state directory is sensitive credential-equivalent data and should have restrictive filesystem permissions.
The download cache is disposable.

Initial Telegram authentication is performed in a dedicated bootstrap/login mode rather than interactively during
normal daemon startup. Verification codes and 2FA passwords are not stored in PostgreSQL or ordinary configuration.

The first implementation officially supports one active service instance owning the Telegram session. PostgreSQL
leasing remains safe for multiple workers/instances, but multiple JVMs do not concurrently open the same TDLight state
directory. Horizontal scaling/leader election is deferred until needed.

### 32.2 Moera credentials

`destinations.config` stores a credential reference rather than the credential value itself. The credential is resolved
from a mounted secret/file or equivalent deployment secret mechanism. Database dumps therefore do not contain Moera
authentication secrets.

The initial configuration uses `credentialRef` with either `env:VARIABLE_NAME` or `file:/absolute/path` syntax. The
referenced environment value or file contains the Moera admin token.

A dedicated abstraction with multiple credential-store implementations is not required initially; a concrete
file/environment-backed implementation is sufficient.

## 33. TelegramSourceAdapter design

Telegram is the first concrete implementation of `SourceAdapter` and is built on TDLight/TDLib.

### 33.1 Channel resolution and identity

Supported source references initially include public usernames/links and private invite links.

For a public Telegram channel, the adapter resolves the link/username to a TDLib chat and stores:

```text
source_id = decimal Telegram chat ID as text
source_uri = supplied/canonical Telegram URI
access_state = GRANTED
subscription_state = INACTIVE unless membership is already active
```

For some private invite links Telegram does not reveal the chat ID before membership/access is granted. Such a channel
is persisted with:

```text
source_id = NULL
source_uri = invite link
```

`refreshAccess(...)` may later return a `resolvedSourceId`. Once a non-null `source_id` has been persisted, it is
immutable.

### 33.2 Telegram access

Private-channel access uses Telegram's invite/join flow.

Conceptually:

```text
UNKNOWN
   |
   +-- access granted immediately -> GRANTED + resolved source_id
   |
   +-- join request submitted ----> REQUESTED
   |
   +-- invalid/expired/banned -----> DENIED
```

`REQUESTED` is checked again according to adapter policy. `DENIED` is used only when denial/loss of access is known
with sufficient confidence; uncertain pending cases are not converted to denial merely because approval has not yet
arrived.

### 33.3 Telegram subscription

For Telegram, event subscription corresponds to being in the state where the TDLib user account is a member/subscriber
and live channel updates are expected.

A public channel can therefore be readable while subscription is inactive:

```text
access = GRANTED
subscription = INACTIVE
```

`refreshSubscription(...)` attempts to join/subscribe. If membership requires approval while the public history remains
readable, subscription may be `ACTIVATING` while access remains `GRANTED`.

Ordinary transient network disconnects do not turn `ACTIVE` into `LOST`; TDLib reconnects and maintains its own local
state. `LOST` represents a meaningful external state change such as loss of membership. If a private channel also
becomes unreadable, access transitions to `DENIED` as appropriate.

### 33.4 Telegram waterline and polling

Telegram waterline is source-specific JSON, conceptually:

```json
{
  "messageId": 123456
}
```

`getChatHistory` is paginated backwards until the scan reaches the prior waterline, then continues through the
configured look-behind window. Results are normalized into chronological order before core ingest.

The new waterline is the newest message ID reached by the successfully persisted forward scan.

**Live updates never advance the waterline.** A message received live is persisted immediately but will intentionally
be observed again by the next poll; idempotent ingest makes this harmless. Only successful polling advances the durable
waterline.

A short/partial TDLib history page is not by itself treated as proof that history has ended; pagination continues
according to the adapter's scan rules.

Polling absence is not considered proof of deletion. In the first version, look-behind primarily detects edits and
fills gaps/new messages.

### 33.5 Telegram live events

The Telegram adapter consumes the TDLib update stream and maps relevant updates to the universal event sink.

The important live paths are conceptually:

```text
new message              -> SourceEvent.MessageChanged
message content edit     -> re-read/map -> SourceEvent.MessageChanged
message pin-state change -> re-read/map -> SourceEvent.MessageChanged
permanent deletion       -> SourceEvent.MessageDeleted
membership changes       -> access/subscription state updates
```

Deletion is propagated only when TDLib indicates a permanent server-side deletion, not when an item merely disappears
from local cache.

Telegram metadata such as chats/supergroups may be cached in memory as recommended by TDLib. Such caches are not
durable application state.

### 33.6 Telegram message mapping

Telegram message IDs become textual `SourceMessage.sourceId` values. Telegram publication timestamps become
`published_at`, and `Message.isPinned` becomes the universal `SourceMessage.pinned` value. A `MessagePinMessage` service
message is not ingested as a separate message.

Telegram formatted text and captions are normalized to sanitized HTML, so Telegram messages normally use:

```text
body_format = HTML
```

Plain text is HTML-escaped. A single line feed becomes `<br>`. Two or more consecutive line feeds form an HTML paragraph
boundary; empty, leading, and trailing paragraphs are not generated, and every non-empty top-level text block is placed
in `<p>`.

The `source_body_hash` is calculated from a canonical representation of every source value that affects normalized body
or link-preview output, not from generated HTML. This includes original Telegram text, ordered formatting entities and
their semantic data, forward attribution, author signature, supported URL buttons, fact-checks, and link-preview
metadata. Explicitly ignored fields are excluded.

Formatting-only edits therefore increment `Message.revision`.

Telegram entity offsets are interpreted according to TDLib semantics inside the Telegram mapper and do not leak into
the universal model.

The mapper uses an explicit allowlist. Its result is either `Mapped(SourceMessage)` or `Skipped(reason)`; it never uses
`null` to represent a deliberate skip. Known skip reasons are low-cardinality values such as `UNSUPPORTED_CONTENT`,
`SERVICE_MESSAGE`, and `INVALID_CONTENT`. A new unknown `MessageContent` is skipped by default rather than serialized,
reflected, or guessed.

A skipped message is a normal successful observation: it does not fail a poll batch, and the waterline advances past
it. A live skipped message/update is ignored. If a previously stored message later maps to a skipped type, its last
supported stored and delivered form remains unchanged. Safe diagnostics include only the content type, channel/message
identity, and reason; source body or sensitive fields are never logged. Unexpected programming failures are not turned
into skips and follow normal retry/diagnostic handling.

#### 33.6.1 Telegram text entities

Formatting maps as follows:

```text
Bold                  -> <b>
Italic                -> <i>
Underline             -> <u>
Strikethrough         -> <s>
Code                  -> <code>
Pre / PreCode         -> <pre>
BlockQuote            -> <blockquote>
ExpandableBlockQuote  -> <blockquote>
```

The `PreCode` language is ignored. Nested/overlapping source ranges are converted to validly nested HTML while retaining
TDLib UTF-16 offset semantics.

`Spoiler` uses `<mr-spoiler>` when it contains no paragraphs or other block elements and `<mr-spoiler-block>` otherwise.
`CustomEmoji` keeps its Unicode text without Telegram animation; its custom emoji ID remains part of the canonical hash.

`Mention` (`@username`) links to `https://t.me/username`. `MentionName` by Telegram user ID remains plain text and does
not expose the numeric ID. URL/TextUrl, email, and phone entities become links only with a safe supported URI scheme;
otherwise their visible text is retained.

Hashtag, cashtag, bot command, bank-card number, media timestamp, and date/time entities remain visible plain text. The
Moera client recognizes literal `#hashtag` values. An unknown future entity also keeps its visible text without special
HTML, while its type and available semantic fields remain in the canonical hash.

#### 33.6.2 Supported structured content

`MessagePoll` is rendered as the formatted question in `<p>` followed by an `<ol>` of formatted option text. Vote
counts, chosen answers, open/closed state, poll description, explanation, and all `PollMedia` are ignored. A quiz adds
the known correct option text after the list:

```html
<mr-spoiler-block title="Right answer">
<p>Correct option</p>
</mr-spoiler-block>
```

Multiple correct options use a `<ul>` inside the spoiler. If TDLib has not revealed the correct answers, the spoiler is
absent; their later appearance changes the message revision.

`MessageChecklist` is rendered as its formatted title followed by tasks marked `☐` or `☑`. Task text, order, addition,
removal, and completion state affect the revision. Completing user/time and checklist permission metadata are ignored.

`MessageLocation` and `MessageLiveLocation` render coordinates linked to OpenStreetMap. Accuracy, heading, live period,
and other live-location metadata are ignored; a coordinate change affects the revision. `MessageVenue` adds its name and
address before the same coordinate link and ignores provider-specific identity/type. `MessageContact` renders the name
and a `tel:` link, while Telegram user ID and vCard are ignored.

`MessageAnimatedEmoji` renders only its Unicode emoji. `MessageSticker` renders its associated emoji and maps the main
sticker file as ordinary media; an unsupported sticker file format follows normal media skip behavior.

Expired photo/video/video-note/voice-note content maps to a message without media and the plain text:

```text
[Media is no longer available]
```

This deliberately replaces previously available media instead of leaving a stale attachment.

#### 33.6.3 Skipped content

The initial Telegram adapter skips these user-content families:

- dice and stake dice;
- games and invoices;
- paid media;
- forwarded stories;
- giveaways, gifts, Telegram currencies, payments, and their lifecycle messages;
- rich messages (`MessageRichMessage`);
- `MessageUnsupported`.

All administrative/service `MessageContent` types are skipped by default, including membership/chat/topic/video-chat,
payment, bot/Web App/Passport, checklist activity, suggested-post, and custom service messages. Pinning is the sole
service-action exception and is synchronized through the referenced message's `Message.isPinned`, not by importing the
service message itself.

#### 33.6.4 Forwarding, buttons, and message metadata

A forwarded message starts with source attribution. A public channel links to the exact original message when its
message ID is available, otherwise to the channel; a public user links to their Telegram username:

```html
<p><a href="source-url">Source name</a></p>
```

When only a source name is available, it is emitted without a link; when neither is available, attribution is omitted.
Attribution is repeated for every forwarded message. When grouped Telegram messages change attribution, `<hr>` is
inserted before the differently attributed message.

An `authorSignature` is appended after the message content as `<p><i>— Author</i></p>`. Ordinary safe-URL inline buttons
are then appended as links; callbacks, Web Apps, login, payment, and other Telegram-only buttons are ignored. A Telegram
fact-check follows buttons as:

```html
<hr>
<p><b>Fact check</b></p>
<blockquote>formatted fact-check text</blockquote>
```

The fact-check country code is ignored, and no block is emitted when TDLib has no text. Replies are not reproduced as
links or quotations.

Interaction/reaction/view/forward counters, unread reactions, import/via-bot metadata, message effects, sender boost/tag,
paid-message/suggested-post data, summary language, and auto-delete/self-destruct timing are ignored. `canBeSaved`,
`restrictionInfo`, and media `isSecret` do not prevent import; the adapter maps whatever content TDLib actually returns.

#### 33.6.5 Telegram link previews

`MessageText.linkPreview` is normalized into `SourceLinkPreview`, mapping every field supported by Moera: site name,
URL, title, description, publication time, and an optional image-media reference. `linkPreviewOptions` presentation
flags are ignored and excluded from the hash.

The preview image is the largest main photo when available, otherwise an available thumbnail or cover. Only one image
is selected; full preview video/audio/document content is ignored. The selected image is mapped as ordinary media so it
is attached to the posting and is also referenced through `Body.LinkPreview.imageHash`. The Moera client excludes that
same media hash from its ordinary gallery presentation. A missing/skipped preview image does not remove the textual
preview.

### 33.7 Telegram media mapping

Telegram file-bearing message content is mapped to universal `Media` metadata during ingest without downloading the
source file.

The supported ordinary types are photo, animation, audio, document, video, video note, and voice note. Their caption is
the message body; track/performer/duration/dimensions and similar technical metadata are not added to the body. Photos
use the largest available source size. A Telegram live photo imports only its still photo. For video, only the primary
file is imported; alternative qualities, storyboards, cover, and start timestamp are ignored. Thumbnails and covers are
not separate media except when one is deliberately selected as a link-preview image.

`showCaptionAboveMedia`, media `hasSpoiler`, `isSecret`, viewed/listened state, and other Telegram-only presentation flags
are ignored and excluded from canonical hashes.

Where available:

```text
media.source_id   = stable TDLib remote unique ID
media.size        = exact TDLib file size only when known
media.source_data = remote file ID and Telegram file-type information needed to reacquire/download the file later
```

If the stable remote unique ID is unavailable, the adapter may fall back to another stable-enough Telegram remote
identifier.

Estimated/expected file sizes are not treated as exact values for the Moera maximum-size precheck.

`source_hash` includes the source file identity and the source metadata that affects publication semantics, such as
MIME type and filename.

### 33.8 Telegram media download

At publication time, `downloadMedia(...)` uses the stored Telegram remote identifier to reacquire the current TDLib
file object, asks TDLib to download it, copies the resulting TDLib cache file into the core-owned staging path, and
then asks TDLib to delete its cached copy.

The core owns staging cleanup and retry lifecycle. The Telegram adapter owns only the TDLib cache lifecycle.

### 33.9 Telegram grouping

Telegram `media_album_id` stays in source-specific message data and provides a strong grouping hint:

```text
same non-zero media_album_id      -> MUST_GROUP
different non-zero album IDs      -> MUST_SEPARATE
otherwise                         -> UNKNOWN
```

`UNKNOWN` falls back to the Telegram adapter's temporal `groupingThreshold`, which also supports long texts split by
Telegram clients into several consecutive messages.

The Telegram adapter also owns Telegram-specific `settlingDelay`, polling intervals, look-behind size, and
initial-history policy.

## 34. Accepted limitations / non-goals

The initial architecture intentionally accepts:

- only the latest `Message` revision is stored;
- bounded look-behind means sufficiently old edits can be missed;
- no full historical reconciliation;
- publication membership is immutable after creation;
- absence from a polling/feed result alone is not evidence of deletion;
- if a previously imported Telegram message later maps to an intentionally skipped type, its last supported destination
  representation remains until an explicit deletion or later supported update is observed;
- source media is not stored permanently;
- Moera create-post idempotency is currently unavailable, so a rare ambiguous failure can create a duplicate after
  retry;
- staging files are not shared across mirrors;
- no lease heartbeat/renewal in the first implementation.

## 35. Planned source integrations

### 35.1 Telegram — first

Implemented with TDLight. Telegram provides live events, source-specific message/media data, private-channel access
workflows, and strong grouping hints such as media-group IDs.

The database `source_type` enum contains only `TELEGRAM` initially.

### 35.2 RSS — next

RSS will be added only when implemented, including a Flyway migration adding `RSS` to `source_type`.

RSS primarily exercises polling, bounded feed history, weak deletion information, and naturally HTML-oriented content.

### 35.3 Mastodon — later

Mastodon should fit the same universal `Channel` / `Message` / `Media` / `SourceAdapter` model with source-specific
IDs/cursors/data kept behind the adapter.

## 36. Design principles

1. The core models channels, messages, media, mirrors, Moera destinations, and publications; it does not model Telegram
   concepts directly.
2. Source-specific behavior/data stay in `SourceAdapter` plus JSONB source state.
3. Moera-specific delivery behavior/data stay in concrete `MoeraService` plus destination/publication JSONB state.
4. Ingest and delivery are decoupled.
5. Durable domain state lives in PostgreSQL; adapters do not write it directly.
6. Polling and live events feed the same message model.
7. Grouping is source semantics, not mirror configuration.
8. Publication membership is immutable after creation.
9. No network call holds a database transaction open.
10. Claim/lease operations are safe across multiple service instances through `SKIP LOCKED`, lease tokens, and
    conditional completion.
11. Blocking external/database/file work is handled with Java virtual threads rather than Reactor-style reactive
    plumbing.
12. Flyway is the only database-schema source of truth; jOOQ reflects that schema.
13. New source types are added only together with real adapter implementations.

## 37. Repository APIs and application workflows

Repositories are concrete classes implemented with jOOQ. They expose atomic persistence operations rather than generic
CRUD. jOOQ-generated classes and `DSLContext` remain confined to the persistence layer.

### Channel claims

Scheduled channel work is represented by a `ChannelClaim` containing a `ChannelSnapshot`, a `ChannelOperation`
(`ACCESS`, `SUBSCRIPTION`, or `POLL`), and a lease token. `processing_until` remains an internal persistence detail.

`ChannelRepository` exposes separate claim operations for access, subscription, and polling so that the scheduler can
preserve the priority order ACCESS → SUBSCRIPTION → POLL. Completion methods return whether the lease token was
still current; stale completions are discarded.

### Poll ingest

`MessageRepository.ingestPoll(...)` performs the whole successful poll commit in one database transaction:

- verify the channel processing token;
- insert or update all messages and media;
- increment message revisions where source content changed;
- propagate affected publication revisions and publication-media state;
- advance the channel waterline;
- update poll scheduling state;
- clear the lease;
- commit.

If the token is stale, none of the poll result is persisted. Live updates use `MessageRepository.ingest(...)` and do
not advance the waterline.

### Message ingest

Message ingest compares `source_body_hash`, normalized link previews, the media set, media `source_hash` values, pinning,
and deletion state. A real change increments `messages.revision`. The repository synchronizes link previews and the
current media set and marks affected publications dirty in the same transaction. Application code does not calculate
revisions itself.

### Publication repositories

`PublicationRepository` claims only publications whose `revision` differs from `published_revision`, whose
`next_attempt_at` is due, whose lease is available, and whose current publication media are all terminal (`UPLOADED` or
`SKIPPED`). A claim records the `targetRevision` and lease token.

`PublicationMediaRepository` claims pending publication media and returns a context containing the publication,
destination, channel snapshot, and source media needed by the worker. Completion of media upload is conditional on both
the lease token and the expected media `source_hash` still matching.

A separate `MediaRepository` is not needed: source media lifecycle belongs to `MessageRepository`, while destination
delivery state belongs to `PublicationMediaRepository`.

## 38. Application services and schedulers

The application layer consists of four independent workflows.

### Channel workflow

`ChannelScheduler` claims due channel operations in priority order and dispatches each claim to `ChannelProcessor` on
virtual threads. `ChannelProcessor` invokes the matching `SourceAdapter` operation and conditionally completes or
retries the claim through the repositories.

A successful poll is committed through `MessageRepository.ingestPoll(...)`, preserving the invariant that the waterline
advances only after the entire batch is stored successfully.

### Live source events

`SourceEventProcessor` implements `SourceEventSink` and persists normalized source events immediately through the
repositories. There is no separate message-ingest service or durable event bus.

`SourceAdapter` must preserve event order within a channel. For Telegram, TDLib callbacks feed an internal queue and a
sequential consumer normalizes and forwards updates to the event sink. This preserves TDLib update ordering rather than
introducing reordering through unconstrained virtual-thread dispatch.

### Publication building

`PublicationBuilderScheduler` finds mirrors with unassigned messages and invokes `PublicationBuilder`.

Publication building performs no network I/O and therefore does not use leases. `PublicationBuilder.build(mirrorId)`
runs as a short Spring transaction and locks the relevant mirror row with `FOR UPDATE SKIP LOCKED` so that only one
builder can group messages for a mirror at a time.

Messages are considered in `(published_at, id)` order. For adjacent messages:

- `MUST_GROUP` always joins them;
- `MUST_SEPARATE` closes the current group;
- `UNKNOWN` joins them when the gap between adjacent messages is no greater than the source adapter's grouping
  threshold.

A group followed by a known boundary can be created immediately. The final known group is created only after
`settlingDelay` has elapsed since its last message. Historical groups whose messages are already older than the
settling delay are created immediately.

Creating a publication inserts `publications`, `publication_messages`, and the currently effective `publication_media`
atomically. Effective media includes ordinary attachments and the first link-preview image for each distinct URL. New
publications and publication-media rows are scheduled immediately with `next_attempt_at = now()`. Message changes
recalculate this effective set so a previously duplicate preview image can become active.

### Publication media workflow

`PublicationMediaScheduler` claims due pending media and dispatches them to `PublicationMediaProcessor` on virtual
threads.

The processor:

1. loads the publication-media context;
2. obtains Moera capabilities;
3. skips the media immediately if a known source size exceeds the destination limit;
4. obtains or reuses a staging file;
5. downloads through the appropriate `SourceAdapter` when necessary;
6. checks the actual file size;
7. uploads to Moera with `downsize=true`;
8. conditionally completes the claim as `UPLOADED`, `SKIPPED`, retryable failure, or permanent failure.

The processor records the media `source_hash` before doing external work. A completion is accepted only if the current
source hash still matches.

### Publication workflow

`PublicationScheduler` claims ready publications and dispatches them to `PublicationProcessor` on virtual threads.

For a claimed `targetRevision`, `PublicationRepository.loadContext(...)` obtains a consistent in-memory snapshot of the
publication, its messages, uploaded media, destination, and mirror configuration. A short transaction holds an
appropriate row lock while the snapshot is read. After the snapshot is materialized, no database transaction remains
open during Moera calls.

If the source changes while revision N is being published, the persisted `revision` may advance to N+1 while the worker
still publishes its in-memory snapshot for N. Successful completion sets `published_revision = N`, leaving the
publication dirty so that a later worker publishes N+1.

`PublicationProcessor` creates a Moera post when no destination post exists, updates an existing post when the payload
is non-empty, updates feed stories by story ID when effective pinning changes, deletes the post when the new payload is
empty, and performs no external call when both the payload and `destination_post_id` are absent. In the latter case it
simply marks `targetRevision` as published.

## 39. Staging files

Staging paths are deterministic and derived from publication ID, media ID, and source hash, for example:

```
${stagingDir}/<publication-id>/<media-id>-<source-hash>
```

A retry can therefore reuse a file already downloaded by an earlier attempt without storing a path in PostgreSQL.
Changing `Media.source_hash` naturally changes the staging path. Old orphaned staging files are later removed by
cleanup.

After a successful upload, including the transition into durable compression waiting, or after a terminal
skip/failure, the staging file is deleted. It is retained across temporary or outcome-unknown upload failures so that
retries do not need to download the source media again. A `PENDING` row marked as compression-pending therefore does
not justify retaining a staging file: subsequent work reads the already-uploaded original media from Moera.

## 40. Retry handling in processors

Network processors use the same failure pattern:

- temporary failures are rescheduled using `RetryPolicy`;
- outcome-unknown failures are rescheduled the same way but are separately logged and metered;
- permanent failures clear automatic scheduling and retain `last_error`;
- normal terminal media outcomes such as `TOO_LARGE`, `UNSUPPORTED`, and `SOURCE_UNAVAILABLE` become `SKIPPED` rather
  than failures.

No separate retry scheduler is required. Existing schedulers pick work up again when `next_attempt_at` becomes due.

## 41. Concurrency model summary

Two concurrency patterns are intentionally used:

- external/network work: short claim transaction → lease token and expiry → commit → external work →
  conditional completion by token;
- pure database/application work such as publication grouping: short transaction with row locks (`FOR UPDATE SKIP
  LOCKED`) and no lease.

This keeps PostgreSQL transactions short while avoiding unnecessary job infrastructure.

## 42. Testing strategy

Testing is layered so that deterministic core logic is covered without external services, while persistence behavior is
verified against real PostgreSQL semantics.

### 42.1 Unit tests

Pure application/domain logic is tested with ordinary JUnit 5 tests without Spring or PostgreSQL. Important units
include:

```text
publication grouping and settling
RetryPolicy
publication body composition and format conversion
hash canonicalization
media-size decisions
publication create/update/delete decision logic
```

Grouping tests cover temporal thresholds, `MUST_GROUP`, `MUST_SEPARATE`, historical settled groups, and live settling
boundaries.

A `FakeSourceAdapter` supplies deterministic access/subscription/poll/download results and grouping behavior to
application tests. Real Telegram is not required for ordinary core tests.

### 42.2 Persistence integration tests

Repository tests use real PostgreSQL through Testcontainers. H2 or another compatibility database is not used.

The test database is initialized by the same Flyway migrations used in production, and jOOQ operates against the
resulting PostgreSQL schema. Tests explicitly cover PostgreSQL-specific behavior such as:

```text
jsonb and PostgreSQL enums
FOR UPDATE SKIP LOCKED
partial due-work indexes
lease reclaim after expiry
conditional completion by processing_token
atomic poll ingest + waterline advance
```

Important concurrency tests use separate threads/transactions to prove that two workers cannot claim the same row, that
an expired lease can be reclaimed, and that a stale token cannot complete newer work.

Message-ingest integration tests cover creation at revision 0, idempotent re-ingest, text-format changes, link-preview
storage/image relations, pinning, media-set/hash changes, and deletion propagation.

Publication tests cover the race where revision N is published while the stored publication advances to N+1.
Publication-media tests cover invalidation when `Media.source_hash` changes during upload, persistence of distinct Moera
private-media ID/hash values, preview URL deduplication, and activation of a later duplicate preview image.

### 42.3 Telegram tests

`TelegramMessageMapper` is isolated from live network behavior and unit-tested with TDLib objects. Tests cover paragraph
conversion, every supported entity mapping, UTF-16 offsets, nested/overlapping entities, inline/block spoilers, captions,
poll/quiz, checklist, location/venue/contact, stickers/animated emoji, expired media, forward attribution, author
signature, URL buttons, fact-check, link-preview fields/image selection, ordinary media, media-album metadata, remote
file identifiers, and canonical hashes.

Every intentionally skipped content family and the unknown-content default have explicit tests. Mapper tests also prove
that ignored fields do not change hashes, relevant semantic fields do, a deliberate skip does not fail polling, and an
unexpected mapper failure is not silently converted to a skip.

Tests that require a real Telegram account are opt-in smoke/integration tests and are not part of the default CI suite.

### 42.4 Moera tests

`MoeraService` behavior is tested against controlled/fake HTTP or library boundaries where practical. Payload tests
cover structured `Body.linkPreviews`, attaching their images as normal media, using `PrivateMediaFileInfo.hash` rather
than its ID for `imageHash`, first-URL-wins deduplication, and pin/unpin through feed story IDs. Tests requiring a real
Moera node are opt-in smoke tests rather than normal CI requirements.

## 43. Observability and health

The implementation uses SLF4J structured logging, Micrometer, and Spring Boot Actuator.

### 43.1 Logging

Work logs include relevant low-level context such as:

```text
operation
channel_id
publication_id
media_id
processing_token
attempt
target_revision
result
```

Sensitive values are never logged, including message bodies, Telegram invite links, authentication tokens, verification
codes, 2FA passwords, or Moera credentials.

Outcome-unknown Moera create failures are logged distinctly from ordinary temporary failures.

### 43.2 Metrics

Metrics use only low-cardinality labels such as source type, operation type, and result category. IDs and node names
are not metric labels.

Useful counters/timers include conceptually:

```text
source_operations_total
source_operation_duration
publications_created_total
publications_updated_total
publications_deleted_total
publication_failures_total
publication_unknown_outcomes_total
media_uploaded_total
media_skipped_total
media_upload_bytes
poll_messages_found_total
telegram_messages_skipped_total
message_revisions_total
lease_reclaims_total
```

Backlog monitoring emphasizes age of overdue work rather than only queue size, for example oldest overdue
poll/publication/media age.

### 43.3 Liveness and readiness

Liveness means that the JVM/process is functioning; temporary Telegram or Moera outages do not make liveness fail.

Readiness requires PostgreSQL/schema availability and successful initialization of the TDLight session to the point
where scheduled source work can run. Failure of one particular Moera destination does not make the entire service
unready; its publications retry independently.

## 44. Recovery, cleanup, startup, and shutdown

The durable schema and lease model are the recovery mechanism. There is no separate general recovery service.

After restart:

```text
expired channel leases           -> claimable again
expired publication-media leases -> claimable again
expired publication leases       -> claimable again
overdue next_attempt_at           -> schedulers pick work up
Telegram waterline                -> next poll continues safely
```

Startup does not mass-clear expired lease columns. Claim queries treat an absent or expired lease as available.

The recovery invariant is:

> A process may terminate at any instruction boundary. After restart, the system must either continue the operation or
> safely repeat it once its lease expires.

The known exception remains an ambiguous successful Moera create followed by a lost response while the Moera API lacks
create idempotency.

### 44.1 Staging cleanup

A cleanup task runs at startup and periodically. It scans deterministic staging paths, checks current PostgreSQL state,
and removes files that no longer correspond to the current pending `(publication, media, source_hash)` state. A grace
period prevents deletion of files that may belong to an active worker.

Files are removable when the media is already uploaded/skipped, the publication/media row no longer exists, the source
hash has changed, or the file is otherwise orphaned.

TDLight's own download cache is separate from core staging. Correctness does not depend on aggressively cleaning
TDLight cache in the first version.

### 44.2 Startup sequence

Normal daemon startup order is:

```text
1. Spring configuration
2. PostgreSQL connectivity
3. Flyway migration
4. jOOQ/repository initialization
5. TDLight session initialization
6. SourceAdapter.start(eventSink)
7. staging cleanup
8. enable schedulers
9. readiness UP
```

Schedulers do not execute Telegram work before TDLight is ready.

### 44.3 Graceful shutdown

Shutdown stops claiming new work, stops source adapters/live intake, allows already-running workers a bounded grace
period, and then exits. In-flight leases are not forcibly rolled back; after expiry they are safely reclaimed on a
later run.
