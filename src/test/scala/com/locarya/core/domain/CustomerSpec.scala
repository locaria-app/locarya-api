package com.locarya.core.domain

import munit.FunSuite

class CustomerSpec extends FunSuite {

  test("create Customer with valid data succeeds") {
    val customerId = CustomerId.generate
    val email = Email.fromString("customer@example.com").toOption.get
    val cpf = CPF.fromString("123.456.789-09").toOption.get

    val result = Customer.create(
      id = customerId,
      email = email,
      cpf = cpf,
      name = "João Silva"
    )

    assert(result.isRight, "Should create Customer with valid data")
    result.foreach { customer =>
      assertEquals(customer.id, customerId)
      assertEquals(customer.email, email)
      assertEquals(customer.cpf, cpf)
      assertEquals(customer.name, "João Silva")
    }
  }

  test("create Customer with empty name fails") {
    val customerId = CustomerId.generate
    val email = Email.fromString("customer@example.com").toOption.get
    val cpf = CPF.fromString("123.456.789-09").toOption.get

    val result = Customer.create(
      id = customerId,
      email = email,
      cpf = cpf,
      name = ""
    )

    assert(result.isLeft, "Should fail to create Customer with empty name")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidCustomer], "Should return InvalidCustomer error")
    }
  }

  test("create Customer with whitespace-only name fails") {
    val customerId = CustomerId.generate
    val email = Email.fromString("customer@example.com").toOption.get
    val cpf = CPF.fromString("123.456.789-09").toOption.get

    val result = Customer.create(
      id = customerId,
      email = email,
      cpf = cpf,
      name = "   "
    )

    assert(result.isLeft, "Should fail to create Customer with whitespace-only name")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidCustomer], "Should return InvalidCustomer error")
    }
  }

  test("create Customer trims name") {
    val customerId = CustomerId.generate
    val email = Email.fromString("customer@example.com").toOption.get
    val cpf = CPF.fromString("123.456.789-09").toOption.get

    val result = Customer.create(
      id = customerId,
      email = email,
      cpf = cpf,
      name = "  João Silva  "
    )

    assert(result.isRight, "Should create Customer and trim whitespace")
    result.foreach { customer =>
      assertEquals(customer.name, "João Silva")
    }
  }
}
