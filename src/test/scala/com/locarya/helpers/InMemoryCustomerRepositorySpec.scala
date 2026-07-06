package com.locarya.helpers

import cats.effect.IO
import cats.syntax.all.*
import com.locarya.domain.models.*
import munit.CatsEffectSuite

class InMemoryCustomerRepositorySpec extends CatsEffectSuite:

  private def makeCustomer(email: String, cpf: Option[String] = None): IO[Customer] =
    (for
      id    <- CustomerId.fromString(java.util.UUID.randomUUID().toString)
      e     <- Email.fromString(email)
      c     <- cpf.traverse(CPF.fromString)
      cust  <- Customer.create(id, e, c, "Test Customer", None)
    yield cust) match
      case Right(c)  => IO.pure(c)
      case Left(err) => IO.raiseError(new RuntimeException(s"Test setup failed: $err"))

  test("InMemoryCustomerRepository.create raises CustomerError.DuplicateCpf for duplicate CPF") {
    for
      repo  <- InMemoryCustomerRepository.make[IO]
      c1    <- makeCustomer("customer1@example.com", Some("529.982.247-25"))
      c2    <- makeCustomer("customer2@example.com", Some("529.982.247-25"))
      _     <- repo.create(c1)
      err   <- repo.create(c2).attempt
    yield err match
      case Left(_: CustomerError.DuplicateCpf) => ()
      case Left(other)  => fail(s"Expected CustomerError.DuplicateCpf but got: ${other.getClass.getName}: ${other.getMessage}")
      case Right(_)     => fail("Expected create to fail with DuplicateCpf but it succeeded")
  }
