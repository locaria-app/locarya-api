package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.TapirSupport.{ErrorBody, publicBase}
import com.locarya.domain.models.*
import com.locarya.domain.ports.{BookingChargeService, ChargeOutcome}
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.Http4sServerInterpreter

object StorefrontChargeRoutes:

  private case class ChargeResponse(paymentUrl: String)
  private given Codec[ChargeResponse]  = deriveCodec
  private given Schema[ChargeResponse] = Schema.derived

  private sealed trait ChargeApiError
  private object ChargeApiError:
    final case class Forbidden(error: String) extends ChargeApiError
    final case class NotFound(error: String)  extends ChargeApiError

  private given Codec[ChargeApiError.Forbidden]  = deriveCodec
  private given Schema[ChargeApiError.Forbidden] = Schema.derived
  private given Codec[ChargeApiError.NotFound]   = deriveCodec
  private given Schema[ChargeApiError.NotFound]  = Schema.derived

  private val chargeE = publicBase.post
    .in("storefront" / path[String]("slug") / "bookings" / path[String]("id") / "charge")
    .out(statusCode.and(jsonBody[ChargeResponse]))
    .errorOut(
      oneOf[ChargeApiError](
        oneOfVariant(StatusCode.Forbidden, jsonBody[ChargeApiError.Forbidden]),
        oneOfVariant(StatusCode.NotFound,  jsonBody[ChargeApiError.NotFound])
      )
    )

  val allEndpoints: List[AnyEndpoint] = List(chargeE)

  def routes[F[_]: Async](chargeService: BookingChargeService[F]): HttpRoutes[F] =

    val server = chargeE.serverLogic[F] { case (slugStr, idStr) =>
      (for
        slug      <- StorefrontSlug.fromString(slugStr).fold(
                       _ => BookingChargeError.NotFound("Storefront not found").raiseError[F, StorefrontSlug],
                       _.pure[F]
                     )
        bookingId <- BookingId.fromString(idStr).fold(
                       _ => BookingChargeError.NotFound("Invalid booking id").raiseError[F, BookingId],
                       _.pure[F]
                     )
        outcome   <- chargeService.chargeBooking(slug, bookingId)
        resp       = outcome match
                       case ChargeOutcome.Created(url)         => (StatusCode.Created, ChargeResponse(url))
                       case ChargeOutcome.ExistingPending(url) => (StatusCode.Ok,      ChargeResponse(url))
      yield Right(resp))
        .handleErrorWith {
          case _: BookingChargeError.OnlinePaymentNotEnabled =>
            Left(ChargeApiError.Forbidden("Online payment is not enabled for this storefront")).pure[F]
          case e: BookingChargeError.NotFound =>
            Left(ChargeApiError.NotFound(e.getMessage)).pure[F]
          case _ =>
            Left(ChargeApiError.NotFound("Not found")).pure[F]
        }
    }

    Http4sServerInterpreter[F]().toRoutes(List(server))
