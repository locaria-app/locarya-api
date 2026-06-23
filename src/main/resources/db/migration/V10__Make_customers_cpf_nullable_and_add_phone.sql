-- Align customers table with the Customer domain model:
--   cpf is Option[CPF] (nullable) and phone: Option[String] (missing column).
ALTER TABLE customers ALTER COLUMN cpf DROP NOT NULL;
ALTER TABLE customers ADD COLUMN phone VARCHAR(50);
