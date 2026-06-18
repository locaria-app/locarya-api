package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.middleware.AuthMiddleware
import com.locarya.domain.models.*
import com.locarya.domain.ports.{BookingLineInput, BookingService, CreateBookingByProviderRequest, CustomerInput, DashboardBookingView}
import io.circe.Encoder
import io.circe.generic.auto.*
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*
import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl

object DashboardBookingRoutes:

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
    customer:        CustomerBody
  )

  private case class BookingItemResponse(
    itemId:    String,
    quantity:  Int,
    unitPrice: Option[BigDecimal]
  )

  private case class CustomerResponse(name: String, email: String, phone: Option[String])

  private case class BookingListResponse(
    id:              String,
    customer:        CustomerResponse,
    items:           List[BookingItemResponse],
    date:            String,
    deliveryAddress: Option[AddressResponse],
    status:          String,
    totalAmount:     BigDecimal,
    createdBy:       String
  )

  private case class AddressResponse(
    street:       String,
    number:       String,
    neighborhood: String,
    city:         String,
    state:        String,
    cep:          String,
    complement:   Option[String]
  )

  private case class BookingCreateResponse(
    bookingId: String,
    status:    String
  )
  private given Encoder[BookingCreateResponse] = deriveEncoder

  private case class UpdateBookingStatusBody(newStatus: String, reason: Option[String])

  private case class BookingStatusResponse(id: String, status: String)
  private given Encoder[BookingStatusResponse] = deriveEncoder

  private case class ErrorResponseBody(error: String)
  private given Encoder[ErrorResponseBody] = deriveEncoder

  private def toKebab(s: String): String =
    s.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase

  private def parseDate(raw: String): Either[ValidationError, LocalDate] =
    try Right(LocalDate.parse(raw.trim))
    catch case _: DateTimeParseException => Left(InvalidBooking(s"Invalid date: $raw — expected YYYY-MM-DD"))

  private def parseBookingStatus(raw: String): Either[String, BookingStatus] =
    raw match
      case "pending"      => Right(BookingStatus.Pending)
      case "confirmed"    => Right(BookingStatus.Confirmed)
      case "in-progress"  => Right(BookingStatus.InProgress)
      case "completed"    => Right(BookingStatus.Completed)
      case "cancelled"    => Right(BookingStatus.Cancelled)
      case other          => Left(s"Unknown status: $other")

  private def toRequest(body: CreateBookingBody): Either[ValidationError, (List[BookingLineInput], LocalDate, Address, CustomerInput)] =
    for
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
    yield (lines, date, address, CustomerInput(body.customer.name, email, body.customer.phone))

  private def toBookingListResponse(view: DashboardBookingView): BookingListResponse =
    BookingListResponse(
      id              = view.id.value,
      customer        = CustomerResponse(view.customer.name, view.customer.email, view.customer.phone),
      items           = view.items.map {
        case BookedIndividualItem(itemId, qty, unitPrice) => BookingItemResponse(itemId.value, qty, unitPrice.map(_.amount))
        case BookedCombo(comboId, qty, unitPrice)         => BookingItemResponse(comboId.value, qty, unitPrice.map(_.amount))
      },
      date            = view.date.toString,
      deliveryAddress = view.deliveryAddress.map { addr =>
        AddressResponse(addr.street, addr.number, addr.neighborhood, addr.city, addr.state, addr.cep, addr.complement)
      },
      status          = toKebab(view.status.toString),
      totalAmount     = view.totalAmount.amount,
      createdBy       = view.createdBy.toString.toLowerCase
    )

  def routes[F[_]: Async](
    bookingService: BookingService[F],
    jwtSecret:      String
  ): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*

    given EntityDecoder[F, CreateBookingBody]        = jsonOf[F, CreateBookingBody]
    given EntityDecoder[F, UpdateBookingStatusBody]  = jsonOf[F, UpdateBookingStatusBody]

    AuthMiddleware.withProviderId[F](jwtSecret) { rawProviderId =>
      val providerIdF: F[ProviderId] =
        ProviderId.fromString(rawProviderId)
          .fold(err => BookingError.InvalidInput(err).raiseError[F, ProviderId], _.pure[F])

      HttpRoutes.of[F]:

        case req @ GET -> Root / "dashboard" / "bookings" =>
          (for
            pid       <- providerIdF
            statusStr  = req.uri.query.params.get("status")
            status    <- statusStr match
                          case Some(s) =>
                            parseBookingStatus(s)
                              .fold(err => BookingError.InvalidInput(InvalidBooking(err)).raiseError[F, Option[BookingStatus]], Some(_).pure[F])
                          case None => None.pure[F]
            dateFromStr = req.uri.query.params.get("dateFrom")
            dateFrom  <- dateFromStr match
                          case Some(d) =>
                            parseDate(d)
                              .fold(err => BookingError.InvalidInput(err).raiseError[F, Option[LocalDate]], Some(_).pure[F])
                          case None => None.pure[F]
            dateToStr  = req.uri.query.params.get("dateTo")
            dateTo    <- dateToStr match
                          case Some(d) =>
                            parseDate(d)
                              .fold(err => BookingError.InvalidInput(err).raiseError[F, Option[LocalDate]], Some(_).pure[F])
                          case None => None.pure[F]
            bookings  <- bookingService.listBookings(pid, status, dateFrom, dateTo)
            resp      <- Ok(bookings.map(toBookingListResponse).asJson)
          yield resp).handleErrorWith {
            case _: BookingError.ProviderIdNotFound => NotFound(ErrorResponseBody("Provider not found").asJson)
            case e: BookingError                    => BadRequest(ErrorResponseBody(e.getMessage).asJson)
            case _                                  => InternalServerError(ErrorResponseBody("Internal error").asJson)
          }

        case req @ POST -> Root / "dashboard" / "bookings" =>
          req.as[CreateBookingBody].flatMap { body =>
            for
              pid      <- providerIdF
              parsed   <- toRequest(body)
                            .fold(err => BookingError.InvalidInput(err).raiseError[F, (List[BookingLineInput], LocalDate, Address, CustomerInput)], _.pure[F])
              (lines, date, address, customer) = parsed
              bookingReq = CreateBookingByProviderRequest(
                             items           = lines,
                             date            = date,
                             deliveryAddress = address,
                             customer        = customer
                           )
              created  <- bookingService.createBookingByProvider(pid, bookingReq)
              resp     <- Created(
                            BookingCreateResponse(
                              bookingId = created.bookingId.value,
                              status    = toKebab(created.status.toString)
                            ).asJson
                          )
            yield resp
          }.handleErrorWith {
            case e: BookingError.ItemsUnavailable =>
              Conflict(
                ErrorResponseBody(s"Items unavailable: ${e.unavailable.map(_.id.value).mkString(", ")}").asJson
              )
            case _: BookingError.ProviderIdNotFound =>
              NotFound(ErrorResponseBody("Provider not found").asJson)
            case e: BookingError.InvalidInput =>
              BadRequest(ErrorResponseBody(e.getMessage).asJson)
            case _: MalformedMessageBodyFailure =>
              BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
            case _: InvalidMessageBodyFailure =>
              BadRequest(ErrorResponseBody("Invalid request body").asJson)
          }

        case req @ PUT -> Root / "dashboard" / "bookings" / bookingIdStr / "status" =>
          req.as[UpdateBookingStatusBody].flatMap { body =>
            (for
              pid       <- providerIdF
              bookingId <- BookingId.fromString(bookingIdStr)
                             .fold(err => BookingError.InvalidInput(err).raiseError[F, BookingId], _.pure[F])
              newStatus <- parseBookingStatus(body.newStatus)
                             .fold(err => BookingError.InvalidInput(InvalidBooking(err)).raiseError[F, BookingStatus], _.pure[F])
              updated   <- bookingService.updateBookingStatus(pid, bookingId, newStatus, body.reason)
              resp      <- Ok(BookingStatusResponse(id = updated.id.value, status = toKebab(updated.status.toString)).asJson)
            yield resp).handleErrorWith {
              case _: BookingError.BookingNotFound => NotFound(ErrorResponseBody("Booking not found").asJson)
              case e: BookingError.InvalidInput    => BadRequest(ErrorResponseBody(e.getMessage).asJson)
              case _: MalformedMessageBodyFailure  => BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
              case _: InvalidMessageBodyFailure    => BadRequest(ErrorResponseBody("Invalid request body").asJson)
            }
          }.handleErrorWith {
            case _: MalformedMessageBodyFailure => BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
            case _: InvalidMessageBodyFailure   => BadRequest(ErrorResponseBody("Invalid request body").asJson)
          }
    }
