-- The sample feature's records; a new feature's table follows the same shape.
-- cmpose:sharing-off: -- Each item belongs to one user: deleting the account deletes the items (ON DELETE CASCADE).
-- Each item belongs to one space: deleting the space, or its owner's account, deletes the items (ON DELETE CASCADE).
-- The CHECKs repeat the API's limits so no other path can store more. Times are epoch milliseconds.
CREATE TABLE items (
    id          UUID PRIMARY KEY,
    -- cmpose:sharing-off: owner_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    space_id    UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    title       TEXT NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    note        TEXT CHECK (char_length(note) <= 2000),
    due_date    BIGINT,
    created_at  BIGINT NOT NULL,
    updated_at  BIGINT NOT NULL
);
-- Listing pages through the items newest first, by (created_at, id).
-- cmpose:sharing-off: CREATE INDEX items_owner_page ON items(owner_id, created_at DESC, id DESC);
CREATE INDEX items_space_page ON items(space_id, created_at DESC, id DESC);
