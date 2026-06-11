package com.locarya.domain.services

import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import io.circe.Json
import io.circe.syntax.*
import org.mindrot.jbcrypt.BCrypt
import org.typelevel.log4cats.Logger
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}

import java.time.Instant

class AuthServiceImpl[F[_]: Sync: Logger](
  repo:      ProviderRepository[F],
  jwtSecret: String
) extends AuthService[F]:

  private val TokenTtlSeconds = 86400L

  def login(request: LoginRequest): F[LoginResult] =
    for
      email    <- liftEmail(request.email)
      provider <- findProvider(email)
      _        <- verifyPassword(request.password, provider.passwordHash, email.value)
      token    <- mintToken(provider)
      _        <- Logger[F].info(
                    s"""{"event":"LoginSuccessful","providerId":"${provider.id.value}"}"""
                  )
    yield LoginResult(
      token          = token,
      providerId     = provider.id.value,
      name           = provider.tradeName,
      email          = provider.email.value,
      plan           = planToString(provider.plan),
      storefrontSlug = provider.storefrontSlug.value
    )

  private def liftEmail(raw: String): F[Email] =
    Email.fromString(raw).fold(
      _ => AuthError.AccountNotFound(raw).raiseError[F, Email],
      _.pure[F]
    )

  private def findProvider(email: Email): F[Provider] =
    repo.findByEmail(email).flatMap {
      case Some(p) => p.pure[F]
      case None =>
        Logger[F].warn(
          s"""{"event":"LoginFailed","email":"${email.value}","reason":"account_not_found"}"""
        ) >>
        AuthError.AccountNotFound(email.value).raiseError[F, Provider]
    }

  private def verifyPassword(raw: String, hash: String, email: String): F[Unit] =
    Sync[F].blocking(BCrypt.checkpw(raw, hash)).flatMap { matches =>
      if matches then ().pure[F]
      else
        Logger[F].warn(
          s"""{"event":"LoginFailed","email":"$email","reason":"invalid_password"}"""
        ) >>
        AuthError.InvalidCredentials(email).raiseError[F, Unit]
    }

  private def mintToken(provider: Provider): F[String] =
    Sync[F].delay {
      val now     = Instant.now().getEpochSecond
      val content = Json.obj(
        "providerId" -> provider.id.value.asJson,
        "email"      -> provider.email.value.asJson,
        "plan"       -> planToString(provider.plan).asJson
      ).noSpaces
      JwtCirce.encode(
        JwtClaim(
          content    = content,
          issuedAt   = Some(now),
          expiration = Some(now + TokenTtlSeconds)
        ),
        jwtSecret,
        JwtAlgorithm.HS256
      )
    }

  private def planToString(plan: Plan): String = plan match
    case Plan.Freemium => "FREEMIUM"
    case Plan.Premium  => "PREMIUM"
