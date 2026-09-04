ALTER TABLE channels
    ADD CONSTRAINT channels_initial_import_after_matches_mode CHECK (
        (initial_import_mode = 'AFTER' AND initial_import_after IS NOT NULL)
        OR
        (initial_import_mode <> 'AFTER' AND initial_import_after IS NULL)
    );
