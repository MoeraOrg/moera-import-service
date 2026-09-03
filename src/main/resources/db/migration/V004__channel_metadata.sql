ALTER TABLE channels
    ADD COLUMN metadata jsonb NOT NULL DEFAULT '{}'::jsonb;

-- [jooq ignore start]
UPDATE channels
SET metadata = source_data -> 'profile',
    source_data = source_data - 'profile'
WHERE jsonb_typeof(source_data -> 'profile') = 'object';
-- [jooq ignore stop]
