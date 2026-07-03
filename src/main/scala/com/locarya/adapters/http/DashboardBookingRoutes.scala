package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.TapirSupport.{ErrorBody, securedBase, validateBearer}
import com.locarya.domain.models.ValidationError
import com.locarya.domain.models.*
import com.locarya.domain.ports.{BookingLineInput, BookingService, CreateBookingByProviderRequest, CustomerInput, DashboardBookingDetailView, DashboardBookingView}
import io.circe.generic.auto.*
import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.AnyEndpoint
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.Http4sServerInterpreter

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

  private case class AddressResponse(
    street:       String,
    number:       String,
    neighborhood: String,
    city:         String,
    state:        String,
    cep:          String,
    complement:   Option[String]
  )

  private case class BookingListResponse(
    id:              String,
    customer:        CustomerResponse,
    items:           List[BookingItemResponse],
    date:            String,
    deliveryAddress: Option[AddressResponse],
    status:          String,
    totalAmount:     BigDecimal,
    createdBy:       String,
    bookingCode:     String
  )

  private case class BookingCreateResponse(bookingId: String, status: String)

  private case class UpdateBookingStatusBody(newStatus: String, reason: Option[String])

  private case class BookingStatusResponse(id: String, status: String)

  private case class AttendantResponse(id: String, name: String, phone: String)

  private case class BookingDetailResponse(
    id:                 String,
    customer:           CustomerResponse,
    items:              List[BookingItemResponse],
    date:               String,
    deliveryAddress:    Option[AddressResponse],
    status:             String,
    totalAmount:        BigDecimal,
    createdBy:          String,
    bookingCode:        String,
    assignedAttendants: List[AttendantResponse]
  )

  private def toKebab(s: String): String =
    s.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase

  private def parseDate(raw: String): Either[ValidationError, LocalDate] =
    try Right(LocalDate.parse(raw.trim))
    catch case _: DateTimeParseException => Left(InvalidBooking(s"Invalid date: $raw — expected YYYY-MM-DD"))

  private def parseBookingStatus(raw: String): Either[String, BookingStatus] =
    raw match
      case "pending"     => Right(BookingStatus.Pending)
      case "confirmed"   => Right(BookingStatus.Confirmed)
      case "in-progress" => Right(BookingStatus.InProgress)
      case "completed"   => Right(BookingStatus.Completed)
      case "cancelled"   => Right(BookingStatus.Cancelled)
      case other         => Left(s"Unknown status: $other")

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

  private def toBookingDetailResponse(view: DashboardBookingDetailView): BookingDetailResponse =
    val base = toBookingListResponse(DashboardBookingView(
      id              = view.id,
      providerId      = view.providerId,
      customer        = view.customer,
      items           = view.items,
      date            = view.date,
      deliveryAddress = view.deliveryAddress,
      status          = view.status,
      totalAmount     = view.totalAmount,
      createdBy       = view.createdBy,
      bookingCode     = view.bookingCode
    ))
    BookingDetailResponse(
      id                 = base.id,
      customer           = base.customer,
      items              = base.items,
      date               = base.date,
      deliveryAddress    = base.deliveryAddress,
      status             = base.status,
      totalAmount        = base.totalAmount,
      createdBy          = base.createdBy,
      bookingCode        = base.bookingCode,
      assignedAttendants = view.assignedAttendants.map(a => AttendantResponse(a.id.value, a.name, a.phone))
    )

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
      createdBy       = view.createdBy.toString.toLowerCase,
      bookingCode     = view.bookingCode.value
    )

  private val listE = securedBase.get
    .in("dashboard" / "bookings")
    .in(query[Option[String]]("status"))
    .in(query[Option[String]]("dateFrom"))
    .in(query[Option[String]]("dateTo"))
    .out(jsonBody[List[BookingListResponse]])

  private val createE = securedBase.post
    .in("dashboard" / "bookings")
    .in(jsonBody[CreateBookingBody])
    .out(statusCode(StatusCode.Created).and(jsonBody[BookingCreateResponse]))

  private val updateStatusE = securedBase.put
    .in("dashboard" / "bookings" / path[String]("bookingId") / "status")
    .in(jsonBody[UpdateBookingStatusBody])
    .out(jsonBody[BookingStatusResponse])

  private val getDetailE = securedBase.get
    .in("dashboard" / "bookings" / path[String]("bookingId"))
    .out(jsonBody[BookingDetailResponse])

  val allEndpoints: List[AnyEndpoint] = List(listE, createE, updateStatusE, getDetailE)

  def routes[F[_]: Async](
    bookingService: BookingService[F],
    jwtSecret:      String
  ): HttpRoutes[F] =

    type Err = (StatusCode, ErrorBody)

    def security(token: String): F[Either[Err, ProviderId]] =
      validateBearer(token, jwtSecret).pure[F]

    def notFound(msg: String): Err   = (StatusCode.NotFound, ErrorBody(msg))
    def badRequest(msg: String): Err = (StatusCode.BadRequest, ErrorBody(msg))
    def conflict(msg: String): Err   = (StatusCode.Conflict, ErrorBody(msg))

    val listServer = listE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => input =>
        val (statusStr, dateFromStr, dateToStr) = input
        (for
          status   <- statusStr match
                        case Some(s) =>
                          parseBookingStatus(s)
                            .fold(err => BookingError.InvalidInput(InvalidBooking(err)).raiseError[F, Option[BookingStatus]], Some(_).pure[F])
                        case None => None.pure[F]
          dateFrom <- dateFromStr match
                        case Some(d) =>
                          parseDate(d)
                            .fold(err => BookingError.InvalidInput(err).raiseError[F, Option[LocalDate]], Some(_).pure[F])
                        case None => None.pure[F]
          dateTo   <- dateToStr match
                        case Some(d) =>
                          parseDate(d)
                            .fold(err => BookingError.InvalidInput(err).raiseError[F, Option[LocalDate]], Some(_).pure[F])
                        case None => None.pure[F]
          bookings <- bookingService.listBookings(providerId, status, dateFrom, dateTo)
        yield Right(bookings.map(toBookingListResponse)))
          .handleErrorWith {
            case _: BookingError.ProviderIdNotFound => Left(notFound("Provider not found")).pure[F]
            case e: BookingError                    => Left(badRequest(e.getMessage)).pure[F]
            case _                                  => Left((StatusCode.InternalServerError, ErrorBody("Internal error"))).pure[F]
          }
      }

    val createServer = createE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => body =>
        (for
          parsed  <- toRequest(body)
                       .fold(err => BookingError.InvalidInput(err).raiseError[F, (List[BookingLineInput], LocalDate, Address, CustomerInput)], _.pure[F])
          (lines, date, address, customer) = parsed
          req      = CreateBookingByProviderRequest(
                       items           = lines,
                       date            = date,
                       deliveryAddress = address,
                       customer        = customer
                     )
          created <- bookingService.createBookingByProvider(providerId, req)
        yield Right(BookingCreateResponse(
          bookingId = created.bookingId.value,
          status    = toKebab(created.status.toString)
        )))
          .handleErrorWith {
            case e: BookingError.ItemsUnavailable   =>
              Left(conflict(s"Items unavailable: ${e.unavailable.map(_.id.value).mkString(", ")}")).pure[F]
            case _: BookingError.ProviderIdNotFound =>
              Left(notFound("Provider not found")).pure[F]
            case e: BookingError.InvalidInput       =>
              Left(badRequest(e.getMessage)).pure[F]
          }
      }

    val updateStatusServer = updateStatusE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => input =>
        val (bookingIdStr, body) = input
        (for
          bookingId <- BookingId.fromString(bookingIdStr)
                         .fold(err => BookingError.InvalidInput(err).raiseError[F, BookingId], _.pure[F])
          newStatus <- parseBookingStatus(body.newStatus)
                         .fold(err => BookingError.InvalidInput(InvalidBooking(err)).raiseError[F, BookingStatus], _.pure[F])
          updated   <- bookingService.updateBookingStatus(providerId, bookingId, newStatus, body.reason)
        yield Right(BookingStatusResponse(id = updated.id.value, status = toKebab(updated.status.toString))))
          .handleErrorWith {
            case _: BookingError.BookingNotFound => Left(notFound("Booking not found")).pure[F]
            case e: BookingError.InvalidInput    => Left(badRequest(e.getMessage)).pure[F]
          }
      }

    val getDetailServer = getDetailE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => bookingIdStr =>
        (for
          bookingId <- BookingId.fromString(bookingIdStr)
                         .fold(err => BookingError.InvalidInput(err).raiseError[F, BookingId], _.pure[F])
          detail    <- bookingService.getBookingDetail(providerId, bookingId)
        yield Right(toBookingDetailResponse(detail)))
          .handleErrorWith {
            case _: BookingError.BookingNotFound => Left(notFound("Booking not found")).pure[F]
            case e: BookingError.InvalidInput    => Left(badRequest(e.getMessage)).pure[F]
          }
      }

    Http4sServerInterpreter[F]().toRoutes(List(listServer, createServer, updateStatusServer, getDetailServer))
