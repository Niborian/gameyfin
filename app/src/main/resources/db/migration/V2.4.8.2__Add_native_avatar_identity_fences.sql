-- Native evidence only, NOT grant/descriptor provisioning. No image/media changes.
ALTER TABLE users ADD COLUMN avatar_identity_epoch UUID NOT NULL DEFAULT RANDOM_UUID();
ALTER TABLE users ADD COLUMN avatar_identity_revision BIGINT NOT NULL DEFAULT 0 CHECK(avatar_identity_revision>=0);
CREATE TRIGGER avatar_identity_revision BEFORE UPDATE ON users FOR EACH ROW
    CALL 'org.gameyfin.db.h2.AvatarIdentityRevisionTrigger';

CREATE TABLE avatar_oidc_fence (
    id INTEGER PRIMARY KEY CHECK(id=1),
    revision BIGINT NOT NULL CHECK(revision>=0)
);
INSERT INTO avatar_oidc_fence(id,revision) VALUES(1,0);
CREATE TRIGGER avatar_oidc_revision BEFORE INSERT, UPDATE, DELETE ON app_config FOR EACH ROW
    CALL 'org.gameyfin.db.h2.AvatarOidcRevisionTrigger';
