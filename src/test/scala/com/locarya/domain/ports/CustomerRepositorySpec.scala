package com.locarya.domain.ports

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.helpers.InMemoryCustomerRepository
import munit.CatsEffectSuite

class CustomerRepositorySpec extends CatsEffectSuite:

  private def makeRepo: IO[CustomerRepository[IO]] =
    InMemoryCustomerRepository.make[IO]

  private def makeCustomer(email: String = "customer@example.com"): Customer =
    Customer.create(
      id = CustomerId.generate,
      email = Email.fromString(email).toOption.get,
      cpf = Some(CPF.fromString("123.456.789-09").toOption.get),
      name = "Maria Silva"
    ).toOption.get

  test("create stores customer and findById retrieves it") {
    for
      repo   <- makeRepo
      c       = makeCustomer()
      stored <- repo.create(c)
      found  <- repo.findById(c.id)
    yield
      assertEquals(stored, c)
      assertEquals(found, Some(c))
  }

  test("findById returns None for missing customer") {
    for
      repo  <- makeRepo
      found <- repo.findById(CustomerId.generate)
    yield assertEquals(found, None)
  }

  test("findByEmail returns Some on match") {
    for
      repo  <- makeRepo
      c      = makeCustomer()
      _     <- repo.create(c)
      found <- repo.findByEmail(c.email)
    yield assertEquals(found, Some(c))
  }

  test("findByEmail returns None on miss") {
    for
      repo  <- makeRepo
      found <- repo.findByEmail(Email.fromString("nobody@example.com").toOption.get)
    yield assertEquals(found, None)
  }

  test("update overwrites customer fields") {
    for
      repo    <- makeRepo
      c        = makeCustomer()
      _       <- repo.create(c)
      updated  = Customer.create(c.id, c.email, c.cpf, "Maria Oliveira").toOption.get
      saved   <- repo.update(updated)
      found   <- repo.findById(c.id)
    yield
      assertEquals(saved.name, "Maria Oliveira")
      assertEquals(found.map(_.name), Some("Maria Oliveira"))
  }

  test("create with duplicate id raises in F") {
    for
      repo   <- makeRepo
      c       = makeCustomer()
      _      <- repo.create(c)
      result <- repo.create(c).attempt
    yield assert(result.isLeft, "Expected duplicate create to fail")
  }
