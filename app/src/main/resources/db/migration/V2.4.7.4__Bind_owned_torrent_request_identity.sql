-- Existing requests are not assigned invented creator/process proofs or digests.
ALTER TABLE owned_torrent_request ADD COLUMN planned_snapshot_id UUID;
UPDATE owned_torrent_request SET planned_snapshot_id = COALESCE(snapshot_id, id);
ALTER TABLE owned_torrent_request ALTER COLUMN planned_snapshot_id SET NOT NULL;
ALTER TABLE owned_torrent_request ADD COLUMN creator_scope VARCHAR(64);
ALTER TABLE owned_torrent_request ADD COLUMN creator_pid BIGINT;
ALTER TABLE owned_torrent_request ADD COLUMN creator_started_millis BIGINT;
ALTER TABLE owned_torrent_request ADD COLUMN host_request_digest VARCHAR(64);
ALTER TABLE owned_torrent_request ADD COLUMN creator_exit_evidence_sha256 VARCHAR(64);
ALTER TABLE owned_torrent_request ADD COLUMN creator_exit_expected_scope VARCHAR(64);
ALTER TABLE owned_torrent_request ADD COLUMN creator_exit_expected_pid BIGINT;
ALTER TABLE owned_torrent_request ADD COLUMN creator_exit_expected_started_millis BIGINT;
ALTER TABLE owned_torrent_request ADD COLUMN creator_exit_container_id VARCHAR(64);
ALTER TABLE owned_torrent_request ADD COLUMN creator_exit_actor_id BIGINT;
ALTER TABLE owned_torrent_request ADD COLUMN creator_exit_at TIMESTAMP WITH TIME ZONE;
