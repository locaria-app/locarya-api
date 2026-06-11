package com.locarya.domain.models

sealed abstract class AuthError(message: String) extends RuntimeException(message)

object AuthError:
  final case class InvalidCredentials(email: String)
      extends AuthError(s"Invalid credentials for: $email")

  final case class AccountNotFound(email: String)
      extends AuthError(s"Account not found: $email")
