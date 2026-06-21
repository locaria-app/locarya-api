package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.TapirSupport.{ErrorBody, publicBase}
import com.locarya.domain.models.ValidationError
import com.locarya.domain.models.*
import com.locarya.domain.ports.{AvailabilityService, StorefrontService}
import io.circe.Codec
import io.circe.generic.auto.*
import io.circe.generic.semiauto.deriveCodec
import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.Http4sServerInterpreter

object AvailabilityRoutes:

  private case class ItemAvailabilityResponse(
    id:           String,
    kind:         String,
    available:    Boolean,
    availableQty: Int
  )
  private given Codec[ItemAvailabilityResponse]  = deriveCodec
  private given Schema[ItemAvailabilityResponse] = Schema.derived

  private case class AvailabilityResponse(date: String, items: List[ItemAvailabilityResponse])
  private given Codec[AvailabilityResponse]      = deriveCodec
  private given Schema[AvailabilityResponse]     = Schema.derived

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

  private val availabilityE = publicBase.get
    .in("storefront" / path[String]("slug") / "availability")
    .in(query[String]("date"))
    .in(query[Option[String]]("items"))
    .in(query[Option[String]]("excludeBookingId"))
    .out(jsonBody[AvailabilityResponse])
    .errorOut(statusCode.and(jsonBody[ErrorBody]))

  val allEndpoints: List[AnyEndpoint] = List(availabilityE)

  def routes[F[_]: Async](
    availabilityService: AvailabilityService[F],
    storefrontService:   StorefrontService[F]
  ): HttpRoutes[F] =

    val server = availabilityE.serverLogic[F] { case (slugStr, dateStr, itemsRaw, excludeRaw) =>
      val parsed =
        for
          slug             <- StorefrontSlug.fromString(slugStr)
          date             <- parseDate(dateStr)
          excludeBookingId <- parseExcludeBookingId(excludeRaw)
          itemsOpt         <- itemsRaw match
                                case None      => Right(None)
                                case Some(raw) => parseItems(raw).map(Some(_))
        yield (slug, date, excludeBookingId, itemsOpt)

      parsed match
        case Left(err: ValidationError) =>
          Left((StatusCode.BadRequest, ErrorBody(err.message))).pure[F]
        case Right((slug, date, excludeBookingId, itemsOpt)) =>
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
          yield Right(AvailabilityResponse(date.toString, result.map(toResponse))))
            .handleErrorWith {
              case _: StorefrontError.NotFound =>
                Left((StatusCode.NotFound, ErrorBody("Storefront not found"))).pure[F]
            }
    }

    Http4sServerInterpreter[F]().toRoutes(List(server))
