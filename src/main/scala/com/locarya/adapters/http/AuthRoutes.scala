package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.TapirSupport.{ErrorBody, publicBase}
import com.locarya.domain.models.{AuthError, SignupError}
import com.locarya.domain.ports.{AuthService, LoginRequest, ProviderService, SignupRequest}
import io.circe.generic.auto.*
import org.http4s.HttpRoutes
import org.typelevel.log4cats.Logger
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.Http4sServerInterpreter

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

  private val signupE = publicBase.post
    .in("auth" / "signup")
    .in(jsonBody[SignupRequestBody])
    .out(statusCode(StatusCode.Created).and(jsonBody[SignupResponseBody]))
    .errorOut(statusCode.and(jsonBody[ErrorBody]))

  private val loginE = publicBase.post
    .in("auth" / "login")
    .in(jsonBody[LoginRequestBody])
    .out(jsonBody[LoginResponseBody])
    .errorOut(statusCode.and(jsonBody[ErrorBody]))

  val allEndpoints: List[AnyEndpoint] = List(signupE, loginE)

  def routes[F[_]: Async: Logger](
    providerService: ProviderService[F],
    authService:     AuthService[F]
  ): HttpRoutes[F] =

    type Err = (StatusCode, ErrorBody)

    val signupServer = signupE.serverLogic[F] { body =>
      providerService
        .signup(SignupRequest(
          email    = body.email,
          password = body.password,
          name     = body.name,
          city     = body.city,
          state    = body.state,
          cpf      = body.cpf,
          cnpj     = body.cnpj
        ))
        .map(result => Right(SignupResponseBody(result.providerId.value, result.storefrontSlug.value)))
        .handleErrorWith {
          case SignupError.DuplicateEmail(email) =>
            Left((StatusCode.Conflict, ErrorBody(s"Email already registered: $email"))).pure[F]
          case SignupError.DuplicateDocument(_) =>
            Left((StatusCode.Conflict, ErrorBody("Provider with this document already exists"))).pure[F]
          case SignupError.InvalidInput(err) =>
            Left((StatusCode.BadRequest, ErrorBody(err.toString))).pure[F]
          case ex =>
            Logger[F].error(ex)("Unexpected error during signup") *>
              Left((StatusCode.InternalServerError, ErrorBody("Internal server error"))).pure[F]
        }
    }

    val loginServer = loginE.serverLogic[F] { body =>
      authService
        .login(LoginRequest(email = body.email, password = body.password))
        .map(result => Right(LoginResponseBody(
          token          = result.token,
          id             = result.providerId,
          name           = result.name,
          email          = result.email,
          planId         = result.plan,
          storefrontSlug = result.storefrontSlug
        )))
        .handleErrorWith {
          case _: AuthError =>
            Left((StatusCode.Unauthorized, ErrorBody("Invalid credentials"))).pure[F]
          case ex =>
            Logger[F].error(ex)("Unexpected error during login") *>
              Left((StatusCode.InternalServerError, ErrorBody("Internal server error"))).pure[F]
        }
    }

    Http4sServerInterpreter[F]().toRoutes(List(signupServer, loginServer))
