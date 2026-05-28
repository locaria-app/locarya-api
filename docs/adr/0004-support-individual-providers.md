# ADR 0004: Support for Individual Providers (CPF)

**Status:** Accepted  
**Date:** 2026-05-27  
**Deciders:** Cleiton Queiroz

## Context

The Locarya platform targets small party equipment rental businesses in Brazil. The initial schema required all providers to have a CNPJ (company tax ID), assuming they would be formal businesses.

However, the Brazilian market includes many individuals who rent equipment informally or as MEI (Microempreendedor Individual - Individual Microentrepreneur). These providers:
- May operate with only a CPF (individual tax ID)
- Represent a significant portion of the target market
- Can legally receive payments via Asaas using CPF
- May later formalize and obtain a CNPJ

## Decision

Providers can register with **either CPF or CNPJ** (exactly one required, never both):

**Database schema:**
- Both `cpf` and `cnpj` columns are nullable
- CHECK constraint ensures exactly one is populated
- `business_name` field serves dual purpose: razão social for CNPJ, legal name for CPF

**Domain model:**
- Provider validation accepts either CPF or CNPJ
- Payment processing (Asaas split payment) works identically for both

## Consequences

### Positive
- **Larger addressable market:** Can onboard individuals and MEI operators immediately
- **Lower barrier to entry:** Don't force formalization to use the platform
- **Natural upgrade path:** Individuals can later add CNPJ without creating new account
- **Payment gateway support:** Asaas natively handles both CPF and CNPJ recipients

### Negative
- **Schema complexity:** Must maintain invariant (exactly one tax ID)
- **Legal/tax differences:** CPF vs CNPJ may have different tax obligations (provider's responsibility, not platform's)
- **Display logic:** UI must handle both "razão social" and "nome completo" appropriately

### Neutral
- Contract terms between platform and provider may vary by tax ID type (future consideration)
- Some features might be CNPJ-only in future (e.g., issuing NFe), but not in MVP

## Alternatives Considered

**CNPJ-only (rejected):** Simpler schema, but excludes significant market segment. Forces informal providers to formalize or use competitors.

**Separate tables for individuals vs companies (rejected):** Over-engineering for MVP. Single providers table with conditional validation is sufficient.

**Allow both CPF and CNPJ simultaneously (rejected):** No valid use case. Creates ambiguity for payment routing and legal identity.

## References
- Asaas API documentation: accepts both CPF and CNPJ for split payment recipients
- Brazilian MEI program: allows individuals to formalize with limited bureaucracy
