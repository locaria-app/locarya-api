ALTER TABLE bookings
  ADD COLUMN confirmed_without_monitor BOOLEAN NOT NULL DEFAULT FALSE;
