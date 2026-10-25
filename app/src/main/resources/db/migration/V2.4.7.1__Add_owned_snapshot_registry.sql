-- No cascading deletion: leases and source/operation identity survive host restart.
CREATE TABLE owned_snapshot_admission_record (id INTEGER PRIMARY KEY CHECK (id = 1));
INSERT INTO owned_snapshot_admission_record(id) VALUES (1);
CREATE TABLE owned_snapshot_record (
    id UUID PRIMARY KEY,
    operation UUID NOT NULL UNIQUE,
    game_id BIGINT NOT NULL,
    variant_id BIGINT NOT NULL,
    request BLOB NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    reserved_bytes BIGINT NOT NULL CHECK (reserved_bytes > 0),
    host_request_digest VARCHAR(64) CHECK (host_request_digest IS NULL OR REGEXP_LIKE(host_request_digest, '^[0-9a-f]{64}$')),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    state VARCHAR(32) NOT NULL CHECK (CASE state WHEN 'RESERVED' THEN TRUE WHEN 'IN_FLIGHT' THEN TRUE WHEN 'READY' THEN TRUE WHEN 'UNCERTAIN' THEN TRUE WHEN 'RETIRING' THEN TRUE WHEN 'RETIRED' THEN TRUE ELSE FALSE END),
    manifest BLOB,
    manifest_digest VARCHAR(64),
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL,
    worker_scope VARCHAR(64), worker_pid BIGINT, worker_started_millis BIGINT,
    creator_scope VARCHAR(64), creator_pid BIGINT, creator_started_millis BIGINT,
    worker_exit_proved_at TIMESTAMP(6) WITH TIME ZONE,
    preparation BLOB,
    CHECK ((worker_scope IS NULL AND worker_pid IS NULL AND worker_started_millis IS NULL) OR (worker_scope IS NOT NULL AND worker_pid IS NOT NULL AND worker_started_millis IS NOT NULL AND worker_pid > 0 AND worker_started_millis >= 0)),
    CHECK ((creator_scope IS NULL AND creator_pid IS NULL AND creator_started_millis IS NULL) OR (creator_scope IS NOT NULL AND creator_pid IS NOT NULL AND creator_started_millis IS NOT NULL AND creator_pid > 0 AND creator_started_millis >= 0)),
    CHECK (state <> 'READY' OR (manifest IS NOT NULL AND manifest_digest IS NOT NULL))
);
CREATE TABLE owned_snapshot_lease_record (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES owned_snapshot_record(id),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    released_at TIMESTAMP(6) WITH TIME ZONE,
    client_id UUID,
    client_ownership_id UUID,
    worker_scope VARCHAR(64), worker_pid BIGINT, worker_started_millis BIGINT,
    creator_scope VARCHAR(64), creator_pid BIGINT, creator_started_millis BIGINT,
    CHECK ((worker_scope IS NULL AND worker_pid IS NULL AND worker_started_millis IS NULL) OR (worker_scope IS NOT NULL AND worker_pid IS NOT NULL AND worker_started_millis IS NOT NULL AND worker_pid > 0 AND worker_started_millis >= 0)),
    CHECK ((creator_scope IS NULL AND creator_pid IS NULL AND creator_started_millis IS NULL) OR (creator_scope IS NOT NULL AND creator_pid IS NOT NULL AND creator_started_millis IS NOT NULL AND creator_pid > 0 AND creator_started_millis >= 0)),
    CHECK ((client_id IS NULL AND client_ownership_id IS NULL) OR (client_id IS NOT NULL AND client_ownership_id IS NOT NULL))
);
CREATE INDEX owned_snapshot_active_lease ON owned_snapshot_lease_record(snapshot_id, released_at);
CREATE TABLE owned_snapshot_consumer_record (
    id UUID PRIMARY KEY,
    lease_id UUID NOT NULL REFERENCES owned_snapshot_lease_record(id),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    released_at TIMESTAMP(6) WITH TIME ZONE,
    worker_scope VARCHAR(64), worker_pid BIGINT, worker_started_millis BIGINT,
    creator_scope VARCHAR(64), creator_pid BIGINT, creator_started_millis BIGINT,
    CHECK ((worker_scope IS NULL AND worker_pid IS NULL AND worker_started_millis IS NULL) OR (worker_scope IS NOT NULL AND worker_pid IS NOT NULL AND worker_started_millis IS NOT NULL AND worker_pid > 0 AND worker_started_millis >= 0)),
    CHECK ((creator_scope IS NULL AND creator_pid IS NULL AND creator_started_millis IS NULL) OR (creator_scope IS NOT NULL AND creator_pid IS NOT NULL AND creator_started_millis IS NOT NULL AND creator_pid > 0 AND creator_started_millis >= 0))
);
CREATE INDEX owned_snapshot_active_consumer ON owned_snapshot_consumer_record(lease_id, released_at);
-- Explicit audited administrator proof for a foreign/recreated PID namespace. Never
-- deletes the primary external-client lease or any filesystem tree by itself.
CREATE TABLE owned_snapshot_recovery_receipt (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES owned_snapshot_record(id),
    operation UUID NOT NULL,
    -- H2 2.4.240 constant-IN comparator retains the creating session; equivalent CASE
    -- keeps this check valid after Flyway closes its migration connection.
    target VARCHAR(16) NOT NULL CHECK (CASE target WHEN 'ACQUISITION' THEN TRUE WHEN 'SESSION' THEN TRUE WHEN 'CONSUMER' THEN TRUE ELSE FALSE END),
    lease_id UUID REFERENCES owned_snapshot_lease_record(id),
    consumer_id UUID REFERENCES owned_snapshot_consumer_record(id),
    expected_scope VARCHAR(64) NOT NULL,
    expected_pid BIGINT NOT NULL CHECK (expected_pid > 0),
    expected_started_millis BIGINT NOT NULL CHECK (expected_started_millis >= 0),
    evidence_digest VARCHAR(64) NOT NULL,
    administrator VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CHECK ((target = 'ACQUISITION' AND lease_id IS NULL AND consumer_id IS NULL)
        OR (target = 'SESSION' AND lease_id IS NOT NULL AND consumer_id IS NULL)
        OR (target = 'CONSUMER' AND lease_id IS NOT NULL AND consumer_id IS NOT NULL))
);
