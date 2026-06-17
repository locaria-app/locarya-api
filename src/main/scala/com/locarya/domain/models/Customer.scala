package com.locarya.domain.models

final case class Customer private (
  id: CustomerId,
  email: Email,
  cpf: Option[CPF],
  name: String,
  phone: Option[String]
)

object Customer {
  def create(
    id: CustomerId,
    email: Email,
    cpf: Option[CPF] = None,
    name: String,
    phone: Option[String] = None
  ): Either[ValidationError, Customer] = {
    if (name.trim.isEmpty) {
      Left(InvalidCustomer("Name cannot be empty"))
    } else {
      Right(Customer(id, email, cpf, name.trim, phone.map(_.trim).filter(_.nonEmpty)))
    }
  }
}
