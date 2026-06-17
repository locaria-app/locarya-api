package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.{AvailabilityService, StorefrontService}
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*
import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl

object AvailabilityRoutes:

  private case class ItemAvailabilityResponse(
    id:           String,
    kind:         String,
    available:    Boolean,
    availableQty: Int
  )
  private given Encoder[ItemAvailabilityResponse] = deriveEncoder

  private case class AvailabilityResponse(date: String, items: List[ItemAvailabilityResponse])
  private given Encoder[AvailabilityResponse] = deriveEncoder

  private case class ErrorResponseBody(error: String)
  private given Encoder[ErrorResponseBody] = deriveEncoder

  private def kindLabel(kind: AvailabilityKind): String = kind match
    case AvailabilityKind.Item    => "item"
    case AvailabilityKind.Combo   => "combo"
    case AvailabilityKind.Unknown => "unknown"

  private def toResponse(a: ItemAvailability): ItemAvailabilityResponse =
    ItemAvailabilityResponse(a.id.value, kindLabel(a.kind), a.available, a.availableQty)

  private def parseItems(raw: String): Either[ValidationError, List[(ItemId, Int)]] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Left(InvalidAvailabilityQuery("items parameter cannot be empty"))
    else
      trimmed.split(",").toList.traverse { part =>
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
      case None      => Right(None)
      case Some(str) => BookingId.fromString(str.trim).map(Some(_))

  def routes[F[_]: Async](
    availabilityService: AvailabilityService[F],
    storefrontService:   StorefrontService[F]
  ): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*

    HttpRoutes.of[F]:
      case req @ GET -> Root / "storefront" / slugStr / "availability" =>
        val parsed: Either[ValidationError, (StorefrontSlug, LocalDate, Option[BookingId], Option[List[(ItemId, Int)]])] =
          for
            slug             <- StorefrontSlug.fromString(slugStr)
            dateRaw          <- req.uri.params.get("date")
                                  .toRight(InvalidAvailabilityQuery("Missing required parameter: date"))
            date             <- parseDate(dateRaw)
            excludeBookingId <- parseExcludeBookingId(req.uri.params.get("excludeBookingId"))
            itemsOpt         <- req.uri.params.get("items") match
                                  case None      => Right(None)
                                  case Some(raw) => parseItems(raw).map(Some(_))
          yield (slug, date, excludeBookingId, itemsOpt)

        parsed match
          case Left(err: ValidationError) =>
            BadRequest(ErrorResponseBody(err.message).asJson)
          case Right((slug, date, excludeBookingId, itemsOpt)) =>
            // No `items` → evaluate the provider's whole active catalog for that date.
            // The Combo's ComboId is reused as an ItemId so the service can dispatch it.
            val requestF: F[List[(ItemId, Int)]] = itemsOpt match
              case Some(list) => list.pure[F]
              case None =>
                storefrontService.getStorefront(slug).map { catalog =>
                  catalog.items.map(wi => wi.item.id -> 1) ++
                    catalog.combos.flatMap(wc => ItemId.fromString(wc.combo.id.value).toOption.map(_ -> 1))
                }

            (for
              request <- requestF
              result  <- availabilityService.checkAvailability(request, date, excludeBookingId)
              resp    <- Ok(AvailabilityResponse(date.toString, result.map(toResponse)).asJson)
             yield resp).handleErrorWith {
              case _: StorefrontError.NotFound =>
                NotFound(ErrorResponseBody("Storefront not found").asJson)
            }
