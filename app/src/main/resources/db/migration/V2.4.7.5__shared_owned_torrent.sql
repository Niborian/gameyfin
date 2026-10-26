CREATE TABLE owned_torrent_shared_owner (
    id UUID PRIMARY KEY REFERENCES owned_torrent_request(id),
    selection_key VARCHAR(64) NOT NULL, profile_digest VARCHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (CASE state WHEN 'PREPARING' THEN TRUE WHEN 'ACTIVE' THEN TRUE
        WHEN 'RETIRING' THEN TRUE WHEN 'RETIRED' THEN TRUE WHEN 'UNCERTAIN' THEN TRUE ELSE FALSE END),
    snapshot_id UUID REFERENCES owned_snapshot_record(id), intent_id UUID REFERENCES qb_distribution_intent(id),
    client_id UUID, lease_id UUID, manifest_digest VARCHAR(64), mapping_digest VARCHAR(64),
    info_hash VARCHAR(40), metadata_digest VARCHAR(64),
    CHECK (state <> 'ACTIVE' OR (snapshot_id IS NOT NULL AND intent_id IS NOT NULL AND client_id IS NOT NULL AND
        lease_id IS NOT NULL AND manifest_digest IS NOT NULL AND mapping_digest IS NOT NULL AND
        info_hash IS NOT NULL AND metadata_digest IS NOT NULL))
);
CREATE INDEX owned_torrent_shared_selection ON owned_torrent_shared_owner(selection_key, state);
CREATE TABLE owned_torrent_shared_reference (
    request_id UUID PRIMARY KEY REFERENCES owned_torrent_request(id),
    owner_id UUID NOT NULL REFERENCES owned_torrent_shared_owner(id),
    state VARCHAR(16) NOT NULL CHECK (CASE state WHEN 'ACTIVE' THEN TRUE WHEN 'RELEASED' THEN TRUE ELSE FALSE END),
    verification_digest VARCHAR(64) NOT NULL, verified_at TIMESTAMP WITH TIME ZONE NOT NULL,
    source_digest VARCHAR(64) NOT NULL, verification_kind VARCHAR(40) NOT NULL, membership_digest VARCHAR(64) NOT NULL
);
CREATE INDEX owned_torrent_shared_refs ON owned_torrent_shared_reference(owner_id, state);
