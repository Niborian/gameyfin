-- Additive state only. Overlay is disabled by default; no legacy bytes/IDs changed.
CREATE TABLE avatar_state (
  user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  avatar_version BIGINT NOT NULL CHECK(avatar_version >= 0),
  -- Combined check below enforces kind too. Avoid H2 2.4 constant-IN CHECK
  -- retaining its closed migration session's string comparator.
  kind VARCHAR(8) NOT NULL,
  content_hash CHAR(64), png_bytes BLOB, legacy_image_id BIGINT,
  CHECK ((kind='ABSENT' AND content_hash IS NULL AND png_bytes IS NULL AND legacy_image_id IS NULL)
      OR (kind='PNG' AND content_hash IS NOT NULL AND png_bytes IS NOT NULL AND legacy_image_id IS NULL AND OCTET_LENGTH(png_bytes)<=1048576)
      OR (kind='LEGACY' AND content_hash IS NULL AND png_bytes IS NULL AND legacy_image_id IS NOT NULL))
);
-- No user FK/cascade: original minimal receipts survive account deletion.
CREATE TABLE avatar_receipt (
  installation_id CHAR(36) NOT NULL, operation_id CHAR(36) NOT NULL,
  intent_hash CHAR(64) NOT NULL, restore_id CHAR(36) NOT NULL,
  configuration_revision BIGINT NOT NULL, image_digest VARCHAR(71) NOT NULL,
  build_checksum CHAR(64) NOT NULL, grant_id CHAR(36) NOT NULL, grant_generation BIGINT NOT NULL,
  binding_digest CHAR(64) NOT NULL, binding_generation BIGINT NOT NULL, user_id BIGINT NOT NULL,
  action VARCHAR(8) NOT NULL, result_version BIGINT NOT NULL, result_hash CHAR(64),
  source_generation BIGINT NOT NULL, committed_ms BIGINT NOT NULL,
  PRIMARY KEY(installation_id,operation_id)
);
