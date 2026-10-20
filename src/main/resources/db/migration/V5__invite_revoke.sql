-- When an owner revoked the invite (epoch milliseconds), null while not revoked. The row stays, so its preview says
-- "revoked" and only an unknown token is a 404.
ALTER TABLE invites ADD COLUMN revoked_at BIGINT;
