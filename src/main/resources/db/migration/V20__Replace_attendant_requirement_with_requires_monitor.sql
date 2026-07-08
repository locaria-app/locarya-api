-- Replace VARCHAR(20) attendant_requirement column with BOOLEAN requires_monitor
-- on both items and combos tables.
--
-- Data mapping: REQUIRED → true, OPTIONAL/NOT_ALLOWED → false.
-- This is intentional and irreversible: the Optional and NotAllowed distinction
-- is collapsed into false per ADR 0011.

ALTER TABLE items
  ADD COLUMN requires_monitor BOOLEAN;
UPDATE items SET requires_monitor = (attendant_requirement = 'REQUIRED');
ALTER TABLE items
  ALTER COLUMN requires_monitor SET NOT NULL,
  DROP COLUMN attendant_requirement;

ALTER TABLE combos
  ADD COLUMN requires_monitor BOOLEAN;
UPDATE combos SET requires_monitor = (attendant_requirement = 'REQUIRED');
ALTER TABLE combos
  ALTER COLUMN requires_monitor SET NOT NULL,
  DROP COLUMN attendant_requirement;
