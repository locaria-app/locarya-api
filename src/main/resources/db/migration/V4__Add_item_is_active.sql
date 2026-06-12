-- Add is_active flag to items for soft-delete support (CONTEXT.md: Items with Bookings
-- cannot be deleted, only deactivated; only active Items appear in the Loja)
ALTER TABLE items ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX idx_items_provider_active ON items(provider_id) WHERE is_active = TRUE;
