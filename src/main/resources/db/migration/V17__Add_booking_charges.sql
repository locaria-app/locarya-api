CREATE TABLE booking_charges (
  id UUID PRIMARY KEY,
  booking_id UUID NOT NULL REFERENCES bookings(id),
  asaas_charge_id VARCHAR NOT NULL UNIQUE,
  payment_url VARCHAR NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_booking_charges_booking ON booking_charges(booking_id);
CREATE INDEX idx_booking_charges_status_created ON booking_charges(status, created_at);
