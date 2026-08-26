ALTER TABLE messages
    ADD COLUMN pinned boolean NOT NULL DEFAULT false;

CREATE TABLE message_link_previews (
    message_id                  bigint NOT NULL
                                    REFERENCES messages(id) ON DELETE CASCADE,
    position                    integer NOT NULL,

    site_name                   text,
    url                         text NOT NULL,
    title                       text,
    description                 text,
    published_at                timestamptz,
    image_media_id              bigint
                                    REFERENCES media(id),

    PRIMARY KEY (message_id, position),

    CHECK (position >= 0)
);

CREATE INDEX message_link_previews_image_media_idx
    ON message_link_previews(image_media_id);
