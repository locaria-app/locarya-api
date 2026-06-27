ALTER TABLE bookings ADD COLUMN booking_code VARCHAR(10) NOT NULL DEFAULT '';
UPDATE bookings SET booking_code = 'LCR-' || UPPER(SUBSTRING(MD5(id::text), 1, 6));
ALTER TABLE bookings ALTER COLUMN booking_code DROP DEFAULT;
ALTER TABLE bookings ADD CONSTRAINT bookings_booking_code_unique UNIQUE (booking_code);
CREATE INDEX idx_bookings_booking_code ON bookings(booking_code);
