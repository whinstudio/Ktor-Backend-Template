-- Sharing: spaces, their members and invite links. Every user gets one personal space on first sign-in.
CREATE TABLE spaces (
    id           UUID PRIMARY KEY,
    owner_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    is_personal  BOOLEAN NOT NULL,
    created_at   BIGINT NOT NULL
);
CREATE INDEX spaces_owner ON spaces(owner_id);
CREATE UNIQUE INDEX spaces_one_personal ON spaces(owner_id) WHERE is_personal;

CREATE TABLE space_members (
    space_id   UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role       TEXT NOT NULL CHECK (role IN ('owner', 'editor', 'viewer')),
    joined_at  BIGINT NOT NULL,
    PRIMARY KEY (space_id, user_id)
);
CREATE INDEX space_members_user ON space_members(user_id);

-- Only the SHA-256 of an invite token is stored; the token itself is handed out once.
CREATE TABLE invites (
    id          UUID PRIMARY KEY,
    space_id    UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    token_hash  TEXT NOT NULL UNIQUE,
    role        TEXT NOT NULL CHECK (role IN ('editor', 'viewer')),
    created_by  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  BIGINT NOT NULL,
    expires_at  BIGINT NOT NULL,
    used_by     UUID REFERENCES users(id) ON DELETE SET NULL,
    used_at     BIGINT
);
CREATE INDEX invites_space ON invites(space_id);
CREATE INDEX invites_created_by ON invites(created_by);

ALTER TABLE files ADD CONSTRAINT files_space_fk FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE CASCADE;
