-- Add soft-delete support to combos table
ALTER TABLE combos ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX idx_combos_is_active ON combos(is_active);
