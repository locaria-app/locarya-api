package com.locarya.helpers

import cats.effect.{Async, Ref}
import cats.syntax.all.*
import com.locarya.domain.ports.{JwtClaims, JwtService}

final class StubJwtService[F[_]: Async] private (state: Ref[F, Map[String, JwtClaims]])
    extends JwtService[F]:

  def sign(claims: JwtClaims): F[String] =
    val token = s"stub-token-${claims.providerId}"
    state.update(_ + (token -> claims)).as(token)

  def verify(token: String): F[JwtClaims] =
    state.get.flatMap { map =>
      map.get(token) match
        case Some(c) => c.pure[F]
        case None    =>
          new RuntimeException(s"StubJwtService: unknown token '$token'").raiseError[F, JwtClaims]
    }

object StubJwtService:
  def make[F[_]: Async]: F[StubJwtService[F]] =
    Ref.of[F, Map[String, JwtClaims]](Map.empty).map(new StubJwtService(_))
