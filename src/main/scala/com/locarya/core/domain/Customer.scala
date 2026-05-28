package com.locarya.core.domain

final case class Customer private (
  id: CustomerId,
  email: Email,
  cpf: CPF,
  name: String
)

object Customer {
  def create(
    id: CustomerId,
    email: Email,
    cpf: CPF,
    name: String
  ): Either[ValidationError, Customer] = {
    if (name.trim.isEmpty) {
      Left(InvalidCustomer("Name cannot be empty"))
    } else {
      Right(Customer(id, email, cpf, name.trim))
    }
  }
}
