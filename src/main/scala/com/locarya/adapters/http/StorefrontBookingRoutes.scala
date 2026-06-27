package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.TapirSupport.{ErrorBody, publicBase}
import com.locarya.domain.models.ValidationError
import com.locarya.domain.models.*
import com.locarya.domain.ports.{BookingLineInput, BookingService, CreateBookingRequest, CustomerInput}
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

object StorefrontBookingRoutes:

  private val confirmationMessage =
    "Sua Reserva foi criada com sucesso e está aguardando confirmação do Locador."

  private case class AddressBody(
    street:       String,
    number:       String,
    neighborhood: String,
    city:         String,
    state:        String,
    cep:          String,
    complement:   Option[String]
  )
  private given Codec[AddressBody]   = deriveCodec
  private given Schema[AddressBody]  = Schema.derived

  private case class CustomerBody(name: String, email: String, phone: Option[String])
  private given Codec[CustomerBody]  = deriveCodec
  private given Schema[CustomerBody] = Schema.derived

  private case class ItemLineBody(itemId: String, quantity: Int)
  private given Codec[ItemLineBody]  = deriveCodec
  private given Schema[ItemLineBody] = Schema.derived

  private case class PartyProfileBody(
    kidsCount: Option[Int]          = None,
    ageGroups: Option[List[String]] = None,
    venueType: Option[String]       = None
  )
  private given Codec[PartyProfileBody]  = deriveCodec
  private given Schema[PartyProfileBody] = Schema.derived

  private case class CreateBookingBody(
    items:           List[ItemLineBody],
    date:            String,
    deliveryAddress: AddressBody,
    customer:        CustomerBody,
    startTime:       Option[String],
    endTime:         Option[String],
    partyProfile:    Option[PartyProfileBody] = None
  )
  private given Codec[CreateBookingBody]  = deriveCodec
  private given Schema[CreateBookingBody] = Schema.derived

  private case class BookingResponse(
    bookingId:           String,
    status:              String,
    totalAmount:         BigDecimal,
    confirmationMessage: String,
    bookingCode:         String
  )
  private given Codec[BookingResponse]   = deriveCodec
  private given Schema[BookingResponse]  = Schema.derived

  // ── 409 Conflict response carries per-item availability so the frontend
  //    can show "only N left" without parsing a string.
  private case class UnavailableItemBody(itemId: String, availableQty: Int)
  private given Codec[UnavailableItemBody]  = deriveCodec
  private given Schema[UnavailableItemBody] = Schema.derived

  private sealed trait BookingApiError
  private object BookingApiError:
    final case class Conflict(error: String, unavailableItems: List[UnavailableItemBody]) extends BookingApiError
    final case class BadRequest(error: String) extends BookingApiError
    final case class NotFound(error: String)   extends BookingApiError

  private given Codec[BookingApiError.Conflict]   = deriveCodec
  private given Schema[BookingApiError.Conflict]  = Schema.derived
  private given Codec[BookingApiError.BadRequest]  = deriveCodec
  private given Schema[BookingApiError.BadRequest] = Schema.derived
  private given Codec[BookingApiError.NotFound]    = deriveCodec
  private given Schema[BookingApiError.NotFound]   = Schema.derived

  private def parseDate(raw: String): Either[ValidationError, LocalDate] =
    try Right(LocalDate.parse(raw.trim))
    catch case _: DateTimeParseException => Left(InvalidBooking(s"Invalid date: $raw — expected YYYY-MM-DD"))

  private def toRequest(slugStr: String, body: CreateBookingBody): Either[ValidationError, CreateBookingRequest] =
    for
      slug    <- StorefrontSlug.fromString(slugStr)
      date    <- parseDate(body.date)
      email   <- Email.fromString(body.customer.email)
      address <- Address.create(
                   street       = body.deliveryAddress.street,
                   number       = body.deliveryAddress.number,
                   neighborhood = body.deliveryAddress.neighborhood,
                   city         = body.deliveryAddress.city,
                   state        = body.deliveryAddress.state,
                   cep          = body.deliveryAddress.cep,
                   complement   = body.deliveryAddress.complement
                 )
      lines   <- body.items.traverse { line =>
                   ItemId.fromString(line.itemId).map(BookingLineInput(_, line.quantity))
                 }
    yield CreateBookingRequest(
      slug            = slug,
      items           = lines,
      date            = date,
      deliveryAddress = address,
      customer        = CustomerInput(body.customer.name, email, body.customer.phone),
      startTime       = body.startTime,
      endTime         = body.endTime,
      partyProfile    = body.partyProfile.map(p => PartyProfile(p.kidsCount, p.ageGroups, p.venueType))
    )

  private val bookingE = publicBase.post
    .in("storefront" / path[String]("slug") / "bookings")
    .in(jsonBody[CreateBookingBody])
    .out(statusCode(StatusCode.Created).and(jsonBody[BookingResponse]))
    .errorOut(
      oneOf[BookingApiError](
        oneOfVariant(StatusCode.Conflict,   jsonBody[BookingApiError.Conflict]),
        oneOfVariant(StatusCode.BadRequest, jsonBody[BookingApiError.BadRequest]),
        oneOfVariant(StatusCode.NotFound,   jsonBody[BookingApiError.NotFound])
      )
    )

  val allEndpoints: List[AnyEndpoint] = List(bookingE)

  def routes[F[_]: Async](bookingService: BookingService[F]): HttpRoutes[F] =

    val server = bookingE.serverLogic[F] { case (slugStr, body) =>
      toRequest(slugStr, body) match
        case Left(err) =>
          Left(BookingApiError.BadRequest(err.message)).pure[F]
        case Right(request) =>
          bookingService.createBooking(request)
            .map { created =>
              Right(BookingResponse(
                bookingId           = created.bookingId.value,
                status              = created.status.toString,
                totalAmount         = created.totalAmount.amount,
                confirmationMessage = confirmationMessage,
                bookingCode         = created.bookingCode.value
              ))
            }
            .handleErrorWith {
              case e: BookingError.ItemsUnavailable =>
                Left(BookingApiError.Conflict(
                  error            = "One or more items are unavailable for the requested date",
                  unavailableItems = e.unavailable.map(a => UnavailableItemBody(a.id.value, a.availableQty))
                )).pure[F]
              case _: BookingError.ProviderNotFound =>
                Left(BookingApiError.NotFound("Storefront not found")).pure[F]
              case e: BookingError.InvalidInput =>
                Left(BookingApiError.BadRequest(e.getMessage)).pure[F]
            }
    }

    Http4sServerInterpreter[F]().toRoutes(List(server))
