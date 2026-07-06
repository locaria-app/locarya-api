package com.locarya.domain.models

sealed abstract class CustomerError(message: String) extends RuntimeException(message)

object CustomerError:
  final case class DuplicateCpf(cpf: String)
      extends CustomerError(s"Customer with this CPF already exists: $cpf")

  final case class DuplicateEmail(email: String)
      extends CustomerError(s"Customer with this email already exists: $email")
