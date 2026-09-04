ALTER TYPE initial_import_mode ADD VALUE 'AFTER';

ALTER TABLE channels
    ADD COLUMN initial_import_after timestamptz;
