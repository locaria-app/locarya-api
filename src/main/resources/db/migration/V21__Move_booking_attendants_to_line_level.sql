-- 1. Add booking_item_id column (nullable first)
ALTER TABLE booking_attendants
  ADD COLUMN booking_item_id UUID REFERENCES booking_items(id) ON DELETE CASCADE;

-- DATA LOSS INTENDED: existing booking-level assignments cannot be deterministically
-- mapped to individual booking items; all rows are wiped. See issue #152.
TRUNCATE booking_attendants;

-- 3. Enforce NOT NULL now that table is empty
ALTER TABLE booking_attendants
  ALTER COLUMN booking_item_id SET NOT NULL;

-- 4. Replace old UNIQUE(booking_id, attendant_id) with UNIQUE(booking_item_id, attendant_id)
ALTER TABLE booking_attendants
  DROP CONSTRAINT IF EXISTS unique_booking_attendant;

ALTER TABLE booking_attendants
  ADD CONSTRAINT unique_booking_item_attendant UNIQUE (booking_item_id, attendant_id);

-- 5. Drop old booking-level index, add new booking-item-level index.
-- Note: idx_booking_attendants_attendant already exists from V1 — do NOT recreate it.
DROP INDEX IF EXISTS idx_booking_attendants_booking;
CREATE INDEX idx_booking_attendants_booking_item ON booking_attendants(booking_item_id);
