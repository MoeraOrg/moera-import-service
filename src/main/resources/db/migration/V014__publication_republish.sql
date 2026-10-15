ALTER TABLE publications
    ADD COLUMN republish_owner_id bigint REFERENCES publications(id) ON DELETE CASCADE,
    ADD COLUMN republish_node_name varchar(135),
    ADD COLUMN republish_post_id varchar(1024),
    ADD COLUMN republish_requested_at timestamptz,
    ADD COLUMN republish_lookup_uris text[] NOT NULL DEFAULT '{}',
    ADD COLUMN republish_deleting boolean NOT NULL DEFAULT false,
    ADD UNIQUE (mirror_id, republish_node_name, republish_post_id);

CREATE INDEX publications_republish_owner_idx ON publications (republish_owner_id);

CREATE TABLE publication_original_messages (
    publication_id bigint NOT NULL REFERENCES publications(id) ON DELETE CASCADE,
    message_id bigint NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    PRIMARY KEY (publication_id, message_id)
);

CREATE INDEX publication_original_messages_message_idx ON publication_original_messages (message_id);
