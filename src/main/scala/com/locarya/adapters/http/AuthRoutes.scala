package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.{AuthError, SignupError}
import com.locarya.domain.ports.{AuthService, LoginRequest, ProviderService, SignupRequest}
import io.circe.generic.auto.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl

object AuthRoutes:

  private case class SignupRequestBody(
    email:    String,
    password: String,
    name:     String,
    city:     String,
    state:    String,
    cpf:      Option[String],
    cnpj:     Option[String]
  )

  private case class SignupResponseBody(
    providerId:     String,
    storefrontSlug: String
  )

  private case class LoginRequestBody(
    email:    String,
    password: String
  )

  private case class LoginResponseBody(
    token:          String,
    id:             String,
    name:           String,
    email:          String,
    planId:         String,
    storefrontSlug: String
  )

  private case class ErrorResponseBody(error: String)

  def routes[F[_]: Async](
    providerService: ProviderService[F],
    authService:     AuthService[F]
  ): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*

    given EntityDecoder[F, SignupRequestBody] = jsonOf[F, SignupRequestBody]
    given EntityDecoder[F, LoginRequestBody]  = jsonOf[F, LoginRequestBody]

    HttpRoutes.of[F]:
      case req @ POST -> Root / "auth" / "signup" =>
        req.as[SignupRequestBody]
          .flatMap { body =>
            providerService
              .signup(
                SignupRequest(
                  email    = body.email,
                  password = body.password,
                  name     = body.name,
                  city     = body.city,
                  state    = body.state,
                  cpf      = body.cpf,
                  cnpj     = body.cnpj
                )
              )
              .flatMap { result =>
                Created(SignupResponseBody(result.providerId.value, result.storefrontSlug.value).asJson)
              }
              .handleErrorWith {
                case SignupError.DuplicateEmail(email) =>
                  Conflict(ErrorResponseBody(s"Email already registered: $email").asJson)
                case SignupError.InvalidInput(err) =>
                  BadRequest(ErrorResponseBody(err.toString).asJson)
                case _ =>
                  InternalServerError()
              }
          }
          .handleErrorWith {
            case _: MalformedMessageBodyFailure =>
              BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
            case _: InvalidMessageBodyFailure =>
              BadRequest(ErrorResponseBody("Invalid request body").asJson)
          }

      case req @ POST -> Root / "auth" / "login" =>
        req.as[LoginRequestBody]
          .flatMap { body =>
            authService
              .login(LoginRequest(email = body.email, password = body.password))
              .flatMap { result =>
                Ok(LoginResponseBody(
                  token          = result.token,
                  id             = result.providerId,
                  name           = result.name,
                  email          = result.email,
                  planId         = result.plan,
                  storefrontSlug = result.storefrontSlug
                ).asJson)
              }
              .handleErrorWith {
                case _: AuthError =>
                  Response[F](Status.Unauthorized)
                    .withEntity(ErrorResponseBody("Invalid credentials").asJson)
                    .pure[F]
                case _ =>
                  InternalServerError()
              }
          }
          .handleErrorWith {
            case _: MalformedMessageBodyFailure =>
              BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
            case _: InvalidMessageBodyFailure =>
              BadRequest(ErrorResponseBody("Invalid request body").asJson)
          }
