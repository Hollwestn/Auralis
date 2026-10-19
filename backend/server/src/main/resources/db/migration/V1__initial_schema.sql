CREATE TABLE accounts (
 id VARCHAR(36) PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE,
 display_name VARCHAR(100) NOT NULL, password_hash VARCHAR(255) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE access_tokens (
 token_hash VARCHAR(64) PRIMARY KEY, user_id VARCHAR(36) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX tokens_user_idx ON access_tokens(user_id);
CREATE TABLE tracks (
 id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 title VARCHAR(300) NOT NULL, artist VARCHAR(300) NOT NULL, album VARCHAR(300) NOT NULL,
 genre VARCHAR(100) NOT NULL, duration_seconds DOUBLE PRECISION NOT NULL,
 size_bytes BIGINT NOT NULL, mime_type VARCHAR(100) NOT NULL,
 storage_path VARCHAR(1000) NOT NULL, content_hash VARCHAR(64) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE(user_id, content_hash)
);
CREATE INDEX tracks_user_idx ON tracks(user_id);
CREATE TABLE favorites (
 user_id VARCHAR(36) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 track_id VARCHAR(36) NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
 PRIMARY KEY(user_id, track_id)
);
CREATE TABLE playlists (
 id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 name VARCHAR(200) NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX playlists_user_idx ON playlists(user_id);
CREATE TABLE playlist_items (
 playlist_id VARCHAR(36) NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
 track_id VARCHAR(36) NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
 position INTEGER NOT NULL, PRIMARY KEY(playlist_id,position)
);
CREATE TABLE listening_history (
 id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 track_id VARCHAR(36) NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
 played_seconds DOUBLE PRECISION NOT NULL, completed BOOLEAN NOT NULL, skipped BOOLEAN NOT NULL,
 played_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX history_user_time_idx ON listening_history(user_id,played_at);
CREATE TABLE feedback (
 user_id VARCHAR(36) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 track_id VARCHAR(36) NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
 action VARCHAR(30) NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(user_id,track_id)
);
CREATE TABLE blocked_artists (
 user_id VARCHAR(36) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 artist VARCHAR(300) NOT NULL, PRIMARY KEY(user_id,artist)
);
CREATE TABLE preferences (
 user_id VARCHAR(36) PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
 genres_json TEXT NOT NULL, artists_json TEXT NOT NULL
);
CREATE TABLE sync_state (
 user_id VARCHAR(36) PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
 version BIGINT NOT NULL, settings_json TEXT NOT NULL, playback_json TEXT NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
