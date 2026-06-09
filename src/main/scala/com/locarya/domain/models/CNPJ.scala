package com.locarya.domain.models

final case class CNPJ private (value: String)

object CNPJ {
  def fromString(cnpj: String): Either[ValidationError, CNPJ] = {
    // Strip formatting (dots, slashes, dashes, spaces)
    val digits = cnpj.replaceAll("[./\\-\\s]", "")

    // Validate length
    if (digits.length != 14) {
      return Left(InvalidCNPJ(s"CNPJ must have 14 digits, got ${digits.length}"))
    }

    // Validate all digits
    if (!digits.forall(_.isDigit)) {
      return Left(InvalidCNPJ("CNPJ must contain only digits"))
    }

    // Check for known invalid CNPJs (all same digits)
    if (digits.distinct.length == 1) {
      return Left(InvalidCNPJ("CNPJ cannot have all same digits"))
    }

    // Validate check digits
    val calculated = calculateCheckDigits(digits.take(12))
    val provided = digits.drop(12)

    if (calculated != provided) {
      return Left(InvalidCNPJ(s"Invalid CNPJ check digits. Expected: $calculated, got: $provided"))
    }

    Right(CNPJ(digits))
  }

  private def calculateCheckDigits(base: String): String = {
    val firstDigit = calculateCheckDigit(base, List(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2))
    val secondDigit = calculateCheckDigit(base + firstDigit, List(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2))
    s"$firstDigit$secondDigit"
  }

  private def calculateCheckDigit(digits: String, weights: List[Int]): Int = {
    val sum = digits.zip(weights).map { case (digit, weight) =>
      digit.asDigit * weight
    }.sum

    val remainder = sum % 11
    if (remainder < 2) 0 else 11 - remainder
  }
}
