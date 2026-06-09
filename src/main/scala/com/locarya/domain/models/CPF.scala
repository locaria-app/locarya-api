package com.locarya.domain.models

final case class CPF private (value: String)

object CPF {
  def fromString(cpf: String): Either[ValidationError, CPF] = {
    // Strip formatting (dots, dashes, spaces)
    val digits = cpf.replaceAll("[.\\-\\s]", "")

    // Validate length
    if (digits.length != 11) {
      return Left(InvalidCPF(s"CPF must have 11 digits, got ${digits.length}"))
    }

    // Validate all digits
    if (!digits.forall(_.isDigit)) {
      return Left(InvalidCPF("CPF must contain only digits"))
    }

    // Check for known invalid CPFs (all same digits)
    if (digits.distinct.length == 1) {
      return Left(InvalidCPF("CPF cannot have all same digits"))
    }

    // Validate check digits
    val calculated = calculateCheckDigits(digits.take(9))
    val provided = digits.drop(9)

    if (calculated != provided) {
      return Left(InvalidCPF(s"Invalid CPF check digits. Expected: $calculated, got: $provided"))
    }

    Right(CPF(digits))
  }

  private def calculateCheckDigits(base: String): String = {
    val firstDigit = calculateCheckDigit(base, 10)
    val secondDigit = calculateCheckDigit(base + firstDigit, 11)
    s"$firstDigit$secondDigit"
  }

  private def calculateCheckDigit(digits: String, startWeight: Int): Int = {
    val sum = digits.zipWithIndex.map { case (digit, idx) =>
      digit.asDigit * (startWeight - idx)
    }.sum

    val remainder = sum % 11
    if (remainder < 2) 0 else 11 - remainder
  }
}
