-- Our own user ids: a sign-in provider's subject (the Firebase uid) only maps to one through user_identities.
-- Times are epoch milliseconds.
CREATE TABLE users (
    id          UUID PRIMARY KEY,
    created_at  BIGINT NOT NULL
);

CREATE TABLE user_identities (
    provider  TEXT NOT NULL,
    subject   TEXT NOT NULL,
    user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email     TEXT,
    PRIMARY KEY (provider, subject)
);
CREATE INDEX user_identities_user ON user_identities(user_id);

-- Uploads; path is the file store's random name, mime the type sniffed from the bytes.
-- cmpose:sharing-off: -- Each file belongs to the user who uploaded it.
-- Each file belongs to a space (V2__sharing.sql adds the foreign key); uploaded_by is kept for the record only.
CREATE TABLE files (
    id           UUID PRIMARY KEY,
    -- cmpose:sharing-off: owner_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    space_id     UUID NOT NULL,
    -- cmpose:sharing
    uploaded_by  UUID REFERENCES users(id) ON DELETE SET NULL,
    -- /cmpose:sharing
    path         TEXT NOT NULL,
    mime         TEXT NOT NULL,
    size         BIGINT NOT NULL,
    created_at   BIGINT NOT NULL
);
-- cmpose:sharing-off: CREATE INDEX files_owner ON files(owner_id);
CREATE INDEX files_space ON files(space_id);
