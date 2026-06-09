package com.locarya.domain.services

// Use-case service implementations land here — see ADR 0006.
//
// Each service implements an inbound port from `com.locarya.domain.ports`,
// orchestrating pure domain logic (`com.locarya.domain.models`) over outbound
// ports. Abstract `F[_]` only — never concrete `IO`, never doobie/http4s. The
// dependency-rule guard (scripts/check-architecture.sh) enforces this.
//
// Empty until the first vertical slice introduces a use-case service.
