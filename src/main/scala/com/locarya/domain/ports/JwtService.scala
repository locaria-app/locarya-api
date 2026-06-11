package com.locarya.domain.ports

final case class JwtClaims(
  providerId: String,
  email:      String,
  plan:       String
)

trait JwtService[F[_]]:
  def sign(claims: JwtClaims): F[String]
  def verify(token: String): F[JwtClaims]
