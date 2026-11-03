ALTER TABLE pairs ADD COLUMN live_draft_consent INTEGER NOT NULL DEFAULT 0 CHECK (live_draft_consent IN (0, 1));
ALTER TABLE pairs ADD COLUMN live_draft_consent_at_ms INTEGER;
