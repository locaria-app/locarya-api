package com.locarya.helpers

// In-memory implementations of the port traits and stub gateways used by
// service / route / use-case tests — see ADR 0007.
//
// e.g. an in-memory `ProviderRepository[F]` backed by a `Ref`, or a stub
// `PaymentGateway[F]` returning canned responses. These let business logic be
// tested without Testcontainers or a real database.
//
// Empty until the first vertical slice introduces a port to fake.
