package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.SignupError
import com.locarya.domain.ports.{ProviderService, SignupRequest}
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
    providerId:    String,
    storefrontUrl: String
  )

  private case class ErrorResponseBody(error: String)

  def routes[F[_]: Async](service: ProviderService[F]): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*

    given EntityDecoder[F, SignupRequestBody] = jsonOf[F, SignupRequestBody]

    HttpRoutes.of[F]:
      case req @ POST -> Root / "auth" / "signup" =>
        req.as[SignupRequestBody]
          .flatMap { body =>
            service
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
                val url = s"https://locarya.com.br/loja/${result.storefrontSlug.value}"
                Created(SignupResponseBody(result.providerId.value, url).asJson)
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
