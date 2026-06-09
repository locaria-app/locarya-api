package com.locarya.domain.models

final case class Email private (value: String)

object Email {
  def fromString(email: String): Either[ValidationError, Email] = {
    val trimmed = email.trim.toLowerCase

    if (trimmed.isEmpty) {
      Left(InvalidEmail("Email cannot be empty"))
    } else if (!trimmed.contains("@")) {
      Left(InvalidEmail("Email must contain @"))
    } else {
      val parts = trimmed.split("@")
      if (parts.length != 2 || parts(0).isEmpty || parts(1).isEmpty) {
        Left(InvalidEmail("Email must have valid format: user@domain"))
      } else {
        Right(Email(trimmed))
      }
    }
  }
}
