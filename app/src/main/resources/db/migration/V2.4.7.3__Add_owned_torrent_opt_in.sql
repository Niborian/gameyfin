-- Dedicated settings: credentials never enter generic config DTOs/subscription streams.
CREATE TABLE owned_torrent_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1), configuration CLOB NOT NULL,
    username VARCHAR(8192), password VARCHAR(8192), revision BIGINT NOT NULL CHECK (revision >= 0)
);
INSERT INTO owned_torrent_settings(id, configuration, revision) VALUES (1, '{}', 0);
CREATE TABLE owned_torrent_request (
    id UUID PRIMARY KEY, owner_key VARCHAR(64) NOT NULL, selection CLOB NOT NULL,
    settings_revision BIGINT NOT NULL, state VARCHAR(24) NOT NULL,
    distribution_id UUID REFERENCES qb_distribution_intent(id), snapshot_id UUID REFERENCES owned_snapshot_record(id),
    profile CLOB NOT NULL, profile_username VARCHAR(8192), profile_password VARCHAR(8192),
    cleanup_username VARCHAR(8192), cleanup_password VARCHAR(8192), cleanup_profile_digest VARCHAR(64),
    cleanup_credential_actor_id BIGINT, cleanup_credential_at TIMESTAMP WITH TIME ZONE, cleanup_credential_revision BIGINT,
    CHECK ((cleanup_username IS NULL AND cleanup_password IS NULL AND cleanup_profile_digest IS NULL AND
        cleanup_credential_actor_id IS NULL AND cleanup_credential_at IS NULL AND cleanup_credential_revision IS NULL) OR
        (cleanup_username IS NOT NULL AND cleanup_password IS NOT NULL AND cleanup_profile_digest IS NOT NULL AND
        cleanup_credential_actor_id IS NOT NULL AND cleanup_credential_at IS NOT NULL AND cleanup_credential_revision IS NOT NULL))
);
