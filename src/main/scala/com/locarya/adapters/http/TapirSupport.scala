package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.ProviderId
import io.circe.generic.auto.*
import pdi.jwt.{JwtAlgorithm, JwtCirce}
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*

import scala.util.{Failure, Success}

object TapirSupport:

  final case class ErrorBody(error: String)

  // Base endpoint with bearer auth security input and unified error output
  val securedBase: Endpoint[String, Unit, (StatusCode, ErrorBody), Unit, Any] =
    endpoint
      .securityIn(auth.bearer[String]())
      .errorOut(statusCode.and(jsonBody[ErrorBody]))

  def validateBearer(
    token:     String,
    jwtSecret: String
  ): Either[(StatusCode, ErrorBody), ProviderId] =
    JwtCirce.decodeJson(token, jwtSecret, Seq(JwtAlgorithm.HS256)) match
      case Failure(_) =>
        Left((StatusCode.Unauthorized, ErrorBody("Invalid or missing token")))
      case Success(json) =>
        json.hcursor.downField("providerId").as[String] match
          case Left(_) =>
            Left((StatusCode.Unauthorized, ErrorBody("Invalid token claims")))
          case Right(rawPid) =>
            ProviderId.fromString(rawPid)
              .leftMap(err => (StatusCode.Unauthorized, ErrorBody(err.toString)))

  def authenticatedEndpoint[F[_]: Async](jwtSecret: String) =
    securedBase.serverSecurityLogic[ProviderId, F] { token =>
      validateBearer(token, jwtSecret).pure[F]
    }
