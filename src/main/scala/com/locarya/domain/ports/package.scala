package com.locarya.domain.ports

// Ports (Tagless Final `F[_]` traits) land here — see ADR 0006.
//
//   - Inbound ports:  use-case service traits the driving HTTP adapters call
//                     (e.g. `trait ProviderService[F[_]]`).
//   - Outbound ports: dependencies the core needs but does not own
//                     (e.g. `trait ProviderRepository[F[_]]`, `trait PaymentGateway[F[_]]`).
//
// Abstract `F[_]` only — never concrete `IO`, never doobie/http4s. The
// dependency-rule guard (scripts/check-architecture.sh) enforces this.
//
// Empty until the first vertical slice introduces a port.
