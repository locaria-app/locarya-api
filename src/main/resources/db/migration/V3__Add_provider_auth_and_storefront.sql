-- Add auth and storefront fields required by Provider Signup (issue #5)
ALTER TABLE providers
  ADD COLUMN password_hash    VARCHAR(60)  NOT NULL DEFAULT '',
  ADD COLUMN plan             VARCHAR(20)  NOT NULL DEFAULT 'FREEMIUM',
  ADD COLUMN storefront_slug  VARCHAR(255) NOT NULL DEFAULT gen_random_uuid()::VARCHAR;

-- Remove bootstrap defaults — application always supplies these values
ALTER TABLE providers
  ALTER COLUMN password_hash   DROP DEFAULT,
  ALTER COLUMN storefront_slug DROP DEFAULT;

-- Enforce slug uniqueness
CREATE UNIQUE INDEX idx_providers_storefront_slug ON providers(storefront_slug);
