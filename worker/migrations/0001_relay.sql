PRAGMA foreign_keys = ON;

CREATE TABLE devices (
  uid TEXT NOT NULL,
  device_id TEXT NOT NULL,
  fcm_token TEXT,
  revoked INTEGER NOT NULL DEFAULT 0,
  updated_at_ms INTEGER NOT NULL,
  PRIMARY KEY (uid, device_id)
);

CREATE TABLE invites (
  token_hash TEXT PRIMARY KEY,
  invite_id TEXT NOT NULL,
  owner_uid TEXT NOT NULL,
  owner_device_id TEXT NOT NULL,
  expires_at_ms INTEGER NOT NULL,
  pair_id TEXT,
  consumed INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY (owner_uid, owner_device_id) REFERENCES devices(uid, device_id)
);
CREATE INDEX invites_expiry ON invites(expires_at_ms);

CREATE TABLE pairs (
  pair_id TEXT PRIMARY KEY,
  owner_uid TEXT NOT NULL,
  owner_device_id TEXT NOT NULL,
  joiner_uid TEXT NOT NULL,
  joiner_device_id TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('pending-confirmation', 'active', 'revoked', 'expired')),
  pairing_status TEXT NOT NULL CHECK (pairing_status IN ('key-package-ready', 'welcome-ready', 'complete', 'revoked', 'expired')),
  joiner_key_package_base64 TEXT,
  welcome_base64 TEXT,
  ratchet_tree_base64 TEXT,
  welcome_expires_at_ms INTEGER,
  owner_confirmed INTEGER NOT NULL DEFAULT 0,
  joiner_confirmed INTEGER NOT NULL DEFAULT 0,
  created_at_ms INTEGER NOT NULL,
  updated_at_ms INTEGER NOT NULL,
  FOREIGN KEY (owner_uid, owner_device_id) REFERENCES devices(uid, device_id),
  FOREIGN KEY (joiner_uid, joiner_device_id) REFERENCES devices(uid, device_id)
);
CREATE INDEX pairs_owner_device ON pairs(owner_uid, owner_device_id);
CREATE INDEX pairs_joiner_device ON pairs(joiner_uid, joiner_device_id);
CREATE INDEX pairs_pending_expiry ON pairs(pairing_status, welcome_expires_at_ms);

CREATE TABLE envelope_receipts (
  pair_id TEXT NOT NULL,
  envelope_id TEXT NOT NULL,
  sender_uid TEXT NOT NULL,
  sender_device_id TEXT NOT NULL,
  payload_sha256 TEXT NOT NULL,
  expires_at_ms INTEGER NOT NULL,
  creation_nonce TEXT NOT NULL,
  PRIMARY KEY (pair_id, envelope_id),
  FOREIGN KEY (pair_id) REFERENCES pairs(pair_id)
);
CREATE INDEX receipt_expiry ON envelope_receipts(expires_at_ms);

CREATE TABLE envelopes (
  pair_id TEXT NOT NULL,
  envelope_id TEXT NOT NULL,
  sender_uid TEXT NOT NULL,
  sender_device_id TEXT NOT NULL,
  recipient_uid TEXT NOT NULL,
  payload_base64 TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL,
  expires_at_ms INTEGER NOT NULL,
  PRIMARY KEY (pair_id, envelope_id),
  FOREIGN KEY (pair_id, envelope_id) REFERENCES envelope_receipts(pair_id, envelope_id)
);
CREATE INDEX envelopes_recipient ON envelopes(pair_id, recipient_uid, expires_at_ms);
CREATE INDEX envelopes_expiry ON envelopes(expires_at_ms);

CREATE TABLE rate_limits (
  uid TEXT NOT NULL,
  action TEXT NOT NULL,
  minute INTEGER NOT NULL,
  count INTEGER NOT NULL,
  expires_at_ms INTEGER NOT NULL,
  PRIMARY KEY (uid, action, minute)
);
CREATE INDEX rate_limit_expiry ON rate_limits(expires_at_ms);
