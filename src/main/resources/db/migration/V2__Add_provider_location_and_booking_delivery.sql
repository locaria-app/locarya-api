-- Providers table updates: add CPF support and location fields
ALTER TABLE providers
  ADD COLUMN cpf VARCHAR(11),
  ADD COLUMN city VARCHAR(100) NOT NULL,
  ADD COLUMN state VARCHAR(2) NOT NULL,
  ALTER COLUMN cnpj DROP NOT NULL;

-- Enforce exactly one tax ID (CPF or CNPJ)
ALTER TABLE providers
  ADD CONSTRAINT check_provider_tax_id
  CHECK ((cpf IS NOT NULL AND cnpj IS NULL) OR (cpf IS NULL AND cnpj IS NOT NULL));

-- Unique index on CPF (partial index allows multiple NULLs)
CREATE UNIQUE INDEX idx_providers_cpf ON providers(cpf) WHERE cpf IS NOT NULL;

-- Bookings table updates: add optional delivery address
ALTER TABLE bookings
  ADD COLUMN delivery_street VARCHAR(255),
  ADD COLUMN delivery_number VARCHAR(20),
  ADD COLUMN delivery_neighborhood VARCHAR(100),
  ADD COLUMN delivery_city VARCHAR(100),
  ADD COLUMN delivery_state VARCHAR(2),
  ADD COLUMN delivery_cep VARCHAR(8),
  ADD COLUMN delivery_complement VARCHAR(255);
