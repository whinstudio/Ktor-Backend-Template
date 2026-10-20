-- The name a user chooses to show; null when they have not set one. The CHECK repeats the API's limit.
ALTER TABLE users ADD COLUMN display_name TEXT CHECK (char_length(display_name) <= 80);
