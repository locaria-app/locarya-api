package com.locarya.domain.ports

final case class LoginResult(
  token:          String,
  providerId:     String,
  name:           String,
  email:          String,
  plan:           String,
  storefrontSlug: String
)

trait AuthService[F[_]]:
  def login(email: String, password: String): F[LoginResult]
