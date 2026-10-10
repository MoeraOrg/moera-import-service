-- Functional indexes do not change generated tables and are unsupported by jOOQ's DDL simulator.
-- [jooq ignore start]
CREATE INDEX messages_external_source_uri_idx ON messages (lower(external_source_uri));
CREATE INDEX channels_public_username_idx ON channels (lower(public_username));
-- [jooq ignore stop]
