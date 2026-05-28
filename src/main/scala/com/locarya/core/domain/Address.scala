package com.locarya.core.domain

final case class Address private (
  street: String,
  number: String,
  neighborhood: String,
  city: String,
  state: String,
  cep: String,
  complement: Option[String]
)

object Address {
  private val validStates = Set(
    "SP", "RJ", "MG", "AC", "AL", "AP", "AM", "BA", "CE", "DF",
    "ES", "GO", "MA", "MT", "MS", "PA", "PB", "PR", "PE", "PI",
    "RN", "RS", "RO", "RR", "SC", "SE", "TO"
  )

  def fromFields(
    street: String,
    number: String,
    neighborhood: String,
    city: String,
    state: String,
    cep: String,
    complement: Option[String]
  ): Either[ValidationError, Address] = {
    // Validate required fields are not empty
    if (street.trim.isEmpty) {
      return Left(InvalidAddress("Street cannot be empty"))
    }
    if (number.trim.isEmpty) {
      return Left(InvalidAddress("Number cannot be empty"))
    }
    if (neighborhood.trim.isEmpty) {
      return Left(InvalidAddress("Neighborhood cannot be empty"))
    }
    if (city.trim.isEmpty) {
      return Left(InvalidAddress("City cannot be empty"))
    }

    // Validate state
    if (!validStates.contains(state)) {
      return Left(InvalidAddress(s"Invalid state code: $state"))
    }

    // Normalize and validate CEP
    val normalizedCep = cep.replace("-", "")

    if (normalizedCep.length != 8) {
      return Left(InvalidAddress(s"CEP must have 8 digits, got ${normalizedCep.length}"))
    }

    if (!normalizedCep.forall(_.isDigit)) {
      return Left(InvalidAddress("CEP must contain only digits"))
    }

    Right(Address(
      street = street,
      number = number,
      neighborhood = neighborhood,
      city = city,
      state = state,
      cep = normalizedCep,
      complement = complement
    ))
  }
}
