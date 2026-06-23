-- Loosen the date range constraint from strict less-than to less-than-or-equal.
-- The domain model (Booking.create) allows startDate == endDate for single-day bookings,
-- which are explicitly supported per CONTEXT.md and tested in BookingRepositorySpec.
-- The original V1 strict CHECK (start_date < end_date) rejects these valid bookings.
ALTER TABLE bookings DROP CONSTRAINT valid_date_range;
ALTER TABLE bookings ADD CONSTRAINT valid_date_range CHECK (start_date <= end_date);
