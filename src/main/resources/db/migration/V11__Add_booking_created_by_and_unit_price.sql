-- Add created_by to bookings: tracks whether a Customer or Provider initiated the booking.
-- Defaults to PROVIDER for all pre-existing rows (safe fallback per domain semantics).
ALTER TABLE bookings
  ADD COLUMN created_by VARCHAR(20) NOT NULL DEFAULT 'PROVIDER';

-- Add unit_price to booking_items: captures the price snapshot at booking-creation time.
-- Nullable because bookings created before this migration have no snapshot (None in the domain).
ALTER TABLE booking_items
  ADD COLUMN unit_price DECIMAL(10, 2);
