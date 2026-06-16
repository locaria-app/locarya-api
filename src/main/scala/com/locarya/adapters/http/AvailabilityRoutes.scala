package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.AvailabilityService
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*
import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl

object AvailabilityRoutes:

  private case class UnavailableItemResponse(itemId: String, reason: String)
  private given Encoder[UnavailableItemResponse] = deriveEncoder

  private case class AvailabilityResponse(
    available:        Boolean,
    unavailableItems: List[UnavailableItemResponse]
  )
  private given Encoder[AvailabilityResponse] = deriveEncoder

  private case class ErrorResponseBody(error: String)
  private given Encoder[ErrorResponseBody] = deriveEncoder

  private def parseItems(raw: String): Either[ValidationError, List[(ItemId, Int)]] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Left(InvalidAvailabilityQuery("items parameter cannot be empty"))
    else
      val parts = trimmed.split(",").toList
      parts.traverse { part =>
        part.split(":").toList match
          case idStr :: qtyStr :: Nil =>
            for
              id  <- ItemId.fromString(idStr.trim)
              qty <- qtyStr.trim.toIntOption
                       .toRight(InvalidAvailabilityQuery(s"Invalid quantity: $qtyStr"))
              ok  <- if qty > 0 then Right(qty)
                     else Left(InvalidAvailabilityQuery(s"Quantity must be positive: $qty"))
            yield (id, ok)
          case _ =>
            Left(InvalidAvailabilityQuery(s"Malformed items entry: $part"))
      }

  private def parseDate(raw: String): Either[ValidationError, LocalDate] =
    try Right(LocalDate.parse(raw.trim))
    catch case _: DateTimeParseException =>
      Left(InvalidAvailabilityQuery(s"Invalid date: $raw — expected YYYY-MM-DD"))

  private def parseExcludeBookingId(raw: Option[String]): Either[ValidationError, Option[BookingId]] =
    raw match
      case None       => Right(None)
      case Some(str)  => BookingId.fromString(str.trim).map(Some(_))

  def routes[F[_]: Async](availabilityService: AvailabilityService[F]): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*

    HttpRoutes.of[F]:
      case req @ GET -> Root / "storefront" / slugStr / "availability" =>
        val parsed: Either[ValidationError, (StorefrontSlug, List[(ItemId, Int)], LocalDate, Option[BookingId])] =
          for
            slug             <- StorefrontSlug.fromString(slugStr)
            itemsRaw         <- req.uri.params.get("items")
                                  .toRight(InvalidAvailabilityQuery("Missing required parameter: items"))
            dateRaw          <- req.uri.params.get("date")
                                  .toRight(InvalidAvailabilityQuery("Missing required parameter: date"))
            items            <- parseItems(itemsRaw)
            date             <- parseDate(dateRaw)
            excludeBookingId <- parseExcludeBookingId(req.uri.params.get("excludeBookingId"))
          yield (slug, items, date, excludeBookingId)

        parsed match
          case Left(err: InvalidAvailabilityQuery) =>
            BadRequest(ErrorResponseBody(err.message).asJson)
          case Left(err: InvalidEntityId) =>
            BadRequest(ErrorResponseBody(err.message).asJson)
          case Left(err: InvalidStorefrontSlug) =>
            BadRequest(ErrorResponseBody(err.message).asJson)
          case Left(_) =>
            BadRequest(ErrorResponseBody("Invalid availability query").asJson)
          case Right((_, items, date, excludeBookingId)) =>
            for
              result <- availabilityService.checkAvailability(items, date, excludeBookingId)
              resp   <- Ok(AvailabilityResponse(
                          available        = result.available,
                          unavailableItems = result.unavailableItems.map(u =>
                                               UnavailableItemResponse(u.itemId.value, u.reason)
                                             )
                        ).asJson)
            yield resp
