ALTER TABLE channels
    ADD COLUMN access_failure_type text,
    ADD COLUMN access_failure_started_at timestamptz,
    ADD COLUMN subscription_failure_type text,
    ADD COLUMN subscription_failure_started_at timestamptz,
    ADD COLUMN poll_failure_type text,
    ADD COLUMN poll_failure_started_at timestamptz,
    ADD CHECK ((access_failure_type IS NULL) = (access_failure_started_at IS NULL)),
    ADD CHECK ((subscription_failure_type IS NULL) = (subscription_failure_started_at IS NULL)),
    ADD CHECK ((poll_failure_type IS NULL) = (poll_failure_started_at IS NULL));

ALTER TABLE publications
    ADD COLUMN failure_type text,
    ADD COLUMN failure_started_at timestamptz,
    ADD CHECK ((failure_type IS NULL) = (failure_started_at IS NULL));

ALTER TABLE publication_media
    ADD COLUMN failure_type text,
    ADD COLUMN failure_started_at timestamptz,
    ADD CHECK ((failure_type IS NULL) = (failure_started_at IS NULL));
