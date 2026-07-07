package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.TapirSupport.{ErrorBody, securedBase, validateBearer}
import com.locarya.domain.models.ProviderId
import com.locarya.domain.ports.{ImageStorageGateway, PresignedUpload}
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto.*
import java.time.Instant
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.AnyEndpoint
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.Http4sServerInterpreter

object DashboardUploadRoutes:

  private val allowedContentTypes = Set("image/jpeg", "image/png", "image/webp")

  private case class UploadRequest(contentType: String)

  private given Encoder[Instant] = Encoder.encodeInstant
  private given Decoder[Instant] = Decoder.decodeInstant

  private val uploadE = securedBase.post
    .in("dashboard" / "uploads")
    .in(jsonBody[UploadRequest])
    .out(jsonBody[PresignedUpload])

  val allEndpoints: List[AnyEndpoint] = List(uploadE)

  def routes[F[_]: Async](
    storageGateway: ImageStorageGateway[F],
    jwtSecret:      String
  ): HttpRoutes[F] =

    type Err = (StatusCode, ErrorBody)

    def security(token: String): F[Either[Err, ProviderId]] =
      validateBearer(token, jwtSecret).pure[F]

    val uploadServer = uploadE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => body =>
        if !allowedContentTypes.contains(body.contentType) then
          Left((StatusCode.BadRequest, ErrorBody(s"Unsupported content type: ${body.contentType}"))).pure[F]
        else
          storageGateway.presignUpload(providerId, body.contentType)
            .map(Right(_))
            .handleError(e => Left((StatusCode.InternalServerError, ErrorBody(e.getMessage))))
      }

    Http4sServerInterpreter[F]().toRoutes(List(uploadServer))
