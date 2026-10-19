ALTER TABLE playlists ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE listening_history ADD COLUMN client_event_id VARCHAR(100);
CREATE UNIQUE INDEX history_client_event_idx ON listening_history(user_id,client_event_id);
