package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.{BookingLineInput, BookingService, CreateBookingRequest, CustomerInput}
import io.circe.Encoder
import io.circe.generic.auto.*
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*
import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl

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

  private case class CustomerBody(name: String, email: String, phone: Option[String])

  private case class ItemLineBody(itemId: String, quantity: Int)

  private case class CreateBookingBody(
    items:           List[ItemLineBody],
    date:            String,
    deliveryAddress: AddressBody,
    customer:        CustomerBody,
    startTime:       Option[String],
    endTime:         Option[String]
  )

  private case class BookingResponse(
    bookingId:           String,
    status:              String,
    totalAmount:         BigDecimal,
    confirmationMessage: String
  )
  private given Encoder[BookingResponse] = deriveEncoder

  private case class UnavailableItemBody(itemId: String, availableQty: Int)
  private given Encoder[UnavailableItemBody] = deriveEncoder

  private case class ConflictBody(error: String, unavailableItems: List[UnavailableItemBody])
  private given Encoder[ConflictBody] = deriveEncoder

  private case class ErrorResponseBody(error: String)
  private given Encoder[ErrorResponseBody] = deriveEncoder

  private def parseDate(raw: String): Either[ValidationError, LocalDate] =
    try Right(LocalDate.parse(raw.trim))
    catch case _: DateTimeParseException => Left(InvalidBooking(s"Invalid date: $raw — expected YYYY-MM-DD"))

  /** Translate the raw request body into a domain [[CreateBookingRequest]], collecting the
    * first validation failure as a `ValidationError`. */
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
      endTime         = body.endTime
    )

  def routes[F[_]: Async](bookingService: BookingService[F]): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*

    given EntityDecoder[F, CreateBookingBody] = jsonOf[F, CreateBookingBody]

    HttpRoutes.of[F]:
      case req @ POST -> Root / "storefront" / slugStr / "bookings" =>
        req.as[CreateBookingBody].flatMap { body =>
          for
            request <- toRequest(slugStr, body)
                         .fold(err => BookingError.InvalidInput(err).raiseError[F, CreateBookingRequest], _.pure[F])
            created <- bookingService.createBooking(request)
            resp    <- Created(
                         BookingResponse(
                           bookingId           = created.bookingId.value,
                           status              = created.status.toString,
                           totalAmount         = created.totalAmount.amount,
                           confirmationMessage = confirmationMessage
                         ).asJson
                       )
          yield resp
        }.handleErrorWith {
          case e: BookingError.ItemsUnavailable =>
            Conflict(
              ConflictBody(
                error            = "One or more items are unavailable for the requested date",
                unavailableItems = e.unavailable.map(a => UnavailableItemBody(a.id.value, a.availableQty))
              ).asJson
            )
          case _: BookingError.ProviderNotFound =>
            NotFound(ErrorResponseBody("Storefront not found").asJson)
          case e: BookingError.InvalidInput =>
            BadRequest(ErrorResponseBody(e.getMessage).asJson)
          case _: MalformedMessageBodyFailure =>
            BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
          case _: InvalidMessageBodyFailure =>
            BadRequest(ErrorResponseBody("Invalid request body").asJson)
        }
