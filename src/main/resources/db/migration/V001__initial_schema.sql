CREATE TYPE source_type AS ENUM (
    'TELEGRAM'
);

CREATE TYPE access_state AS ENUM (
    'UNKNOWN',
    'GRANTED',
    'REQUESTED',
    'DENIED'
);

CREATE TYPE subscription_state AS ENUM (
    'UNSUPPORTED',
    'INACTIVE',
    'ACTIVATING',
    'ACTIVE',
    'FAILED',
    'LOST'
);

CREATE TYPE body_format AS ENUM (
    'PLAIN_TEXT',
    'MARKDOWN',
    'HTML'
);

CREATE TYPE channel_operation AS ENUM (
    'ACCESS',
    'SUBSCRIPTION',
    'POLL'
);

CREATE TYPE publication_media_status AS ENUM (
    'PENDING',
    'UPLOADED',
    'SKIPPED'
);

CREATE TYPE media_skip_reason AS ENUM (
    'TOO_LARGE',
    'UNSUPPORTED',
    'SOURCE_UNAVAILABLE'
);

CREATE TABLE channels (
    id                          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    source_type                 source_type NOT NULL,
    source_id                   varchar(1024),
    source_uri                  varchar(4096) NOT NULL,
    source_data                 jsonb NOT NULL DEFAULT '{}'::jsonb,

    access_state                access_state NOT NULL,
    access_attempts             integer NOT NULL DEFAULT 0,
    access_retry_at             timestamptz,
    access_last_error           text,
    access_data                 jsonb NOT NULL DEFAULT '{}'::jsonb,

    subscription_state          subscription_state NOT NULL,
    subscription_attempts       integer NOT NULL DEFAULT 0,
    subscription_retry_at       timestamptz,
    subscription_last_error     text,
    subscription_data           jsonb NOT NULL DEFAULT '{}'::jsonb,

    waterline                   jsonb,

    last_poll_at                timestamptz,
    next_poll_at                timestamptz,
    poll_attempts               integer NOT NULL DEFAULT 0,
    poll_last_error             text,

    processing_operation        channel_operation,
    processing_token            uuid,
    processing_until            timestamptz,

    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),

    UNIQUE (source_type, source_id),

    CHECK (access_attempts >= 0),
    CHECK (subscription_attempts >= 0),
    CHECK (poll_attempts >= 0),
    CHECK (
        (processing_operation IS NULL
            AND processing_token IS NULL
            AND processing_until IS NULL)
        OR
        (processing_operation IS NOT NULL
            AND processing_token IS NOT NULL
            AND processing_until IS NOT NULL)
    )
);

CREATE TABLE messages (
    id                          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel_id                  bigint NOT NULL
                                    REFERENCES channels(id) ON DELETE CASCADE,

    source_id                   varchar(1024) NOT NULL,

    revision                    integer NOT NULL DEFAULT 0,
    deleted                     boolean NOT NULL DEFAULT false,

    published_at                timestamptz NOT NULL,

    body                        text NOT NULL DEFAULT '',
    body_format                 body_format NOT NULL DEFAULT 'PLAIN_TEXT',
    source_body_hash            bytea NOT NULL,

    source_data                 jsonb NOT NULL DEFAULT '{}'::jsonb,

    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),

    UNIQUE (channel_id, source_id),

    CHECK (revision >= 0),
    CHECK (octet_length(source_body_hash) = 32)
);

CREATE TABLE media (
    id                          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id                  bigint NOT NULL
                                    REFERENCES messages(id) ON DELETE CASCADE,

    source_id                   varchar(1024) NOT NULL,
    position                    integer NOT NULL,

    mime_type                   varchar(255) NOT NULL DEFAULT 'application/octet-stream',
    file_name                   varchar(1024),
    size                        bigint,

    source_hash                 bytea NOT NULL,
    source_data                 jsonb NOT NULL DEFAULT '{}'::jsonb,

    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),

    UNIQUE (message_id, source_id),
    UNIQUE (message_id, position),

    CHECK (position >= 0),
    CHECK (size IS NULL OR size >= 0),
    CHECK (octet_length(source_hash) = 32)
);

CREATE TABLE destinations (
    id                          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    node_name                   varchar(135) NOT NULL,
    config                      jsonb NOT NULL DEFAULT '{}'::jsonb,
    data                        jsonb NOT NULL DEFAULT '{}'::jsonb,

    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),

    UNIQUE (node_name)
);

CREATE TABLE mirrors (
    id                          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    channel_id                  bigint NOT NULL
                                    REFERENCES channels(id) ON DELETE CASCADE,
    destination_id              bigint NOT NULL
                                    REFERENCES destinations(id) ON DELETE CASCADE,

    enabled                     boolean NOT NULL DEFAULT true,
    config                      jsonb NOT NULL DEFAULT '{}'::jsonb,

    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),

    UNIQUE (channel_id, destination_id)
);

CREATE TABLE publications (
    id                          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    mirror_id                   bigint NOT NULL
                                    REFERENCES mirrors(id) ON DELETE CASCADE,

    revision                    integer NOT NULL DEFAULT 0,
    published_revision          integer,

    destination_post_id         varchar(1024),
    destination_data            jsonb NOT NULL DEFAULT '{}'::jsonb,

    attempts                    integer NOT NULL DEFAULT 0,
    next_attempt_at             timestamptz,
    last_error                  text,

    processing_token            uuid,
    processing_until            timestamptz,

    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),

    CHECK (revision >= 0),
    CHECK (
        published_revision IS NULL
        OR (published_revision >= 0 AND published_revision <= revision)
    ),
    CHECK (attempts >= 0),
    CHECK (
        (processing_token IS NULL AND processing_until IS NULL)
        OR
        (processing_token IS NOT NULL AND processing_until IS NOT NULL)
    )
);

CREATE TABLE publication_messages (
    publication_id              bigint NOT NULL
                                    REFERENCES publications(id) ON DELETE CASCADE,
    message_id                  bigint NOT NULL
                                    REFERENCES messages(id) ON DELETE CASCADE,
    position                    integer NOT NULL,

    PRIMARY KEY (publication_id, message_id),
    UNIQUE (publication_id, position),

    CHECK (position >= 0)
);

CREATE TABLE publication_media (
    publication_id              bigint NOT NULL
                                    REFERENCES publications(id) ON DELETE CASCADE,
    media_id                    bigint NOT NULL
                                    REFERENCES media(id) ON DELETE CASCADE,

    status                      publication_media_status NOT NULL DEFAULT 'PENDING',
    skip_reason                 media_skip_reason,

    destination_media_id        varchar(40),
    destination_data            jsonb NOT NULL DEFAULT '{}'::jsonb,

    attempts                    integer NOT NULL DEFAULT 0,
    next_attempt_at             timestamptz,
    last_error                  text,

    processing_token            uuid,
    processing_until            timestamptz,

    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (publication_id, media_id),

    CHECK (attempts >= 0),
    CHECK (
        (status = 'SKIPPED' AND skip_reason IS NOT NULL)
        OR
        (status <> 'SKIPPED' AND skip_reason IS NULL)
    ),
    CHECK (
        (processing_token IS NULL AND processing_until IS NULL)
        OR
        (processing_token IS NOT NULL AND processing_until IS NOT NULL)
    )
);

-- Source ingest and publication grouping.
CREATE INDEX messages_channel_published_idx
    ON messages (channel_id, published_at, id);

-- Channel schedulers. A NULL retry/next timestamp means that the operation
-- is not currently scheduled.
CREATE INDEX channels_access_retry_idx
    ON channels (access_retry_at, id)
    WHERE access_retry_at IS NOT NULL;

CREATE INDEX channels_subscription_retry_idx
    ON channels (subscription_retry_at, id)
    WHERE subscription_retry_at IS NOT NULL;

CREATE INDEX channels_poll_idx
    ON channels (next_poll_at, id)
    WHERE next_poll_at IS NOT NULL;

-- Reverse lookup used when a Message revision changes.
CREATE INDEX publication_messages_message_idx
    ON publication_messages (message_id);

-- Mirrors are already indexed by channel_id through the unique constraint.
CREATE INDEX mirrors_destination_idx
    ON mirrors (destination_id);

CREATE INDEX publications_mirror_idx
    ON publications (mirror_id);

-- A Publication is schedulable only when next_attempt_at is non-NULL.
CREATE INDEX publications_pending_idx
    ON publications (next_attempt_at, id)
    WHERE next_attempt_at IS NOT NULL
      AND published_revision IS DISTINCT FROM revision;

-- Reverse lookup used when source Media changes.
CREATE INDEX publication_media_media_idx
    ON publication_media (media_id);

CREATE INDEX publication_media_pending_idx
    ON publication_media (next_attempt_at, publication_id, media_id)
    WHERE status = 'PENDING'
      AND next_attempt_at IS NOT NULL;
