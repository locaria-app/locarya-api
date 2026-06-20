-- Rename contracts table to subscriptions (frees "Contract" for Phase 2 PDF feature)
ALTER TABLE contracts RENAME TO subscriptions;

-- Rename indexes to match new table name
ALTER INDEX idx_contracts_provider RENAME TO idx_subscriptions_provider;
ALTER INDEX idx_contracts_status RENAME TO idx_subscriptions_status;

-- Drop the denormalised status column from providers (subscription status now lives on subscriptions)
ALTER TABLE providers DROP COLUMN contract_status;
