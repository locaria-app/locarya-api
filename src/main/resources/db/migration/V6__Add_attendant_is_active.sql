-- Add is_active for soft-delete support (blocked by issue #31 for full feature)
ALTER TABLE attendants ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

-- Add phone column (domain model uses name + phone; cpf was in initial schema but not in domain spec)
ALTER TABLE attendants ADD COLUMN phone VARCHAR(50);

-- Make cpf nullable since the domain model does not require it for Attendants
ALTER TABLE attendants ALTER COLUMN cpf DROP NOT NULL;
