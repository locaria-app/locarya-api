package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.TapirSupport.{ErrorBody, securedBase, validateBearer}
import com.locarya.domain.models.*
import com.locarya.domain.ports.ProviderService
import io.circe.{Decoder, Encoder, HCursor}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.Http4sServerInterpreter

object DashboardProviderRoutes:

  private case class UpdateStoreConfigBody(
    primaryColor:   Option[Option[String]],
    logoUrl:        Option[Option[String]],
    whatsappNumber: Option[Option[String]],
    phoneNumber:    Option[Option[String]],
    businessHours:  Option[Option[String]],
    tagline:        Option[Option[String]]
  )

  private given Decoder[UpdateStoreConfigBody] = (c: HCursor) =>
    def field(name: String): Decoder.Result[Option[Option[String]]] =
      c.downField(name).success match
        case None         => Right(None)
        case Some(cursor) => cursor.as[Option[String]].map(Some(_))
    for
      primaryColor   <- field("primaryColor")
      logoUrl        <- field("logoUrl")
      whatsappNumber <- field("whatsappNumber")
      phoneNumber    <- field("phoneNumber")
      businessHours  <- field("businessHours")
      tagline        <- field("tagline")
    yield UpdateStoreConfigBody(primaryColor, logoUrl, whatsappNumber, phoneNumber, businessHours, tagline)

  private given Schema[Option[Option[String]]] =
    Schema.schemaForOption(Schema.schemaForOption(Schema.schemaForString))

  private given Encoder[UpdateStoreConfigBody] = deriveEncoder

  private given Schema[UpdateStoreConfigBody] = Schema.derived

  private case class StoreConfigResponse(
    primaryColor:   Option[String],
    logoUrl:        Option[String],
    whatsappNumber: Option[String],
    phoneNumber:    Option[String],
    businessHours:  Option[String],
    tagline:        Option[String]
  )

  private given Encoder[StoreConfigResponse] = deriveEncoder
  private given Decoder[StoreConfigResponse] = deriveDecoder
  private given Schema[StoreConfigResponse]  = Schema.derived

  private def applyPatch(existing: StoreConfig, patch: UpdateStoreConfigBody): StoreConfig =
    StoreConfig(
      primaryColor   = patch.primaryColor.getOrElse(existing.primaryColor),
      logoUrl        = patch.logoUrl.getOrElse(existing.logoUrl),
      whatsappNumber = patch.whatsappNumber.getOrElse(existing.whatsappNumber),
      phoneNumber    = patch.phoneNumber.getOrElse(existing.phoneNumber),
      businessHours  = patch.businessHours.getOrElse(existing.businessHours),
      tagline        = patch.tagline.getOrElse(existing.tagline)
    )

  private def toResponse(config: StoreConfig): StoreConfigResponse =
    StoreConfigResponse(
      primaryColor   = config.primaryColor,
      logoUrl        = config.logoUrl,
      whatsappNumber = config.whatsappNumber,
      phoneNumber    = config.phoneNumber,
      businessHours  = config.businessHours,
      tagline        = config.tagline
    )

  private val patchE = securedBase.patch
    .in("dashboard" / "store-config")
    .in(jsonBody[UpdateStoreConfigBody])
    .out(jsonBody[StoreConfigResponse])

  val allEndpoints: List[AnyEndpoint] = List(patchE)

  def routes[F[_]: Async](
    providerService: ProviderService[F],
    jwtSecret:       String
  ): HttpRoutes[F] =

    type Err = (StatusCode, ErrorBody)

    def security(token: String): F[Either[Err, ProviderId]] =
      validateBearer(token, jwtSecret).pure[F]

    val patchServer = patchE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => body =>
        (for
          existing  <- providerService.findById(providerId).flatMap {
                         case None    => ProviderError.NotFound(providerId).raiseError[F, Provider]
                         case Some(p) => p.pure[F]
                       }
          newConfig  = applyPatch(existing.storeConfig, body)
          updated   <- providerService.updateStoreConfig(providerId, newConfig)
        yield Right(toResponse(updated.storeConfig)))
          .handleErrorWith {
            case _: ProviderError.NotFound => Left((StatusCode.NotFound, ErrorBody("Provider not found"))).pure[F]
            case _                         => Left((StatusCode.InternalServerError, ErrorBody("Internal error"))).pure[F]
          }
      }

    Http4sServerInterpreter[F]().toRoutes(List(patchServer))
