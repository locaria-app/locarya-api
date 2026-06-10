package com.locarya.domain.models

sealed abstract class SignupError(message: String) extends RuntimeException(message)

object SignupError:
  final case class DuplicateEmail(email: String)
      extends SignupError(s"Email already registered: $email")

  final case class InvalidInput(error: ValidationError)
      extends SignupError(error.toString)
