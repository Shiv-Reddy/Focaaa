CREATE TABLE media_uploads (
  pair_id TEXT NOT NULL,
  media_id TEXT NOT NULL,
  sender_uid TEXT NOT NULL,
  sender_device_id TEXT NOT NULL,
  ciphertext_size INTEGER NOT NULL,
  ciphertext_sha256 TEXT NOT NULL,
  chunk_count INTEGER NOT NULL,
  complete INTEGER NOT NULL DEFAULT 0,
  created_at_ms INTEGER NOT NULL,
  expires_at_ms INTEGER NOT NULL,
  PRIMARY KEY (pair_id, media_id),
  FOREIGN KEY (pair_id) REFERENCES pairs(pair_id)
);
CREATE INDEX media_uploads_expiry ON media_uploads(expires_at_ms);

CREATE TABLE media_chunks (
  pair_id TEXT NOT NULL,
  media_id TEXT NOT NULL,
  chunk_index INTEGER NOT NULL,
  payload_base64 TEXT NOT NULL,
  payload_sha256 TEXT NOT NULL,
  payload_size INTEGER NOT NULL,
  PRIMARY KEY (pair_id, media_id, chunk_index),
  FOREIGN KEY (pair_id, media_id) REFERENCES media_uploads(pair_id, media_id) ON DELETE CASCADE
);
