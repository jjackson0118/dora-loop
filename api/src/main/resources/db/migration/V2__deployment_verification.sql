-- Existing events have no verification evidence. Preserve their v1 payload hashes.
ALTER TABLE deployment_event ADD COLUMN verification TEXT NOT NULL DEFAULT 'UNVERIFIED'
    CHECK (verification IN ('VERIFIED', 'UNVERIFIED'));
