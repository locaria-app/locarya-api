package com.locarya.domain.ports

import com.locarya.domain.models.*

final case class LoginRequest(
  email:    String,
  password: String
)

final case class LoginResult(
  token:          String,
  providerId:     String,
  name:           String,
  email:          String,
  plan:           String,
  storefrontSlug: String
)

trait AuthService[F[_]]:
  def login(request: LoginRequest): F[LoginResult]
