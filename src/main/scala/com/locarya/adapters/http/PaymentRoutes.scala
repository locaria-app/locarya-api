package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.TapirSupport.{ErrorBody, securedBase, validateBearer}
import com.locarya.domain.models.*
import com.locarya.domain.ports.{PaymentService, PaymentSummary}
import io.circe.generic.auto.*
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.AnyEndpoint
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.Http4sServerInterpreter

object PaymentRoutes:

  private case class RecordPaymentBody(
    amount: BigDecimal,
    method: String,
    note:   Option[String]
  )

  private case class PaymentResponse(
    paymentId: String,
    bookingId: String,
    amount:    BigDecimal,
    method:    String,
    status:    String,
    note:      Option[String]
  )

  private case class SummaryResponse(
    total:      BigDecimal,
    paid:       BigDecimal,
    balanceDue: BigDecimal
  )

  private case class ListPaymentsResponse(
    payments: List[PaymentResponse],
    summary:  SummaryResponse
  )

  private def toPaymentResponse(p: Payment): PaymentResponse =
    PaymentResponse(
      paymentId = p.id.value,
      bookingId = p.bookingId.value,
      amount    = p.amount.amount,
      method    = PaymentMethod.encode(p.method),
      status    = p.status.toString.toLowerCase,
      note      = p.note
    )

  private def toSummaryResponse(s: PaymentSummary): SummaryResponse =
    SummaryResponse(s.total, s.paid, s.balanceDue)

  private val recordPaymentE = securedBase.post
    .in("dashboard" / "bookings" / path[String]("bookingId") / "payments")
    .in(jsonBody[RecordPaymentBody])
    .out(statusCode(StatusCode.Created).and(jsonBody[PaymentResponse]))

  private val listPaymentsE = securedBase.get
    .in("dashboard" / "bookings" / path[String]("bookingId") / "payments")
    .out(jsonBody[ListPaymentsResponse])

  val allEndpoints: List[AnyEndpoint] = List(recordPaymentE, listPaymentsE)

  def routes[F[_]: Async](
    paymentService: PaymentService[F],
    jwtSecret:      String
  ): HttpRoutes[F] =

    type Err = (StatusCode, ErrorBody)

    def security(token: String): F[Either[Err, ProviderId]] =
      validateBearer(token, jwtSecret).pure[F]

    def notFound(msg: String): Err   = (StatusCode.NotFound, ErrorBody(msg))
    def badRequest(msg: String): Err = (StatusCode.BadRequest, ErrorBody(msg))
    def forbidden(msg: String): Err  = (StatusCode.Forbidden, ErrorBody(msg))

    val recordServer = recordPaymentE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => input =>
        val (bookingIdStr, body) = input
        (for
          bookingId <- BookingId.fromString(bookingIdStr)
                         .fold(err => PaymentError.InvalidInput(err).raiseError[F, BookingId], _.pure[F])
          method    <- PaymentMethod.decode(body.method)
                         .fold(err => PaymentError.InvalidInput(err).raiseError[F, PaymentMethod], _.pure[F])
          payment   <- paymentService.recordPayment(providerId, bookingId, body.amount, method, body.note)
        yield Right(toPaymentResponse(payment)))
          .handleErrorWith {
            case _: PaymentError.BookingNotFound => Left(notFound("Booking not found")).pure[F]
            case _: PaymentError.Forbidden       => Left(forbidden("Access denied")).pure[F]
            case e: PaymentError.InvalidInput    => Left(badRequest(e.getMessage)).pure[F]
            case e: PaymentError                 => Left(badRequest(e.getMessage)).pure[F]
          }
      }

    val listServer = listPaymentsE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => bookingIdStr =>
        (for
          bookingId <- BookingId.fromString(bookingIdStr)
                         .fold(err => PaymentError.InvalidInput(err).raiseError[F, BookingId], _.pure[F])
          payments  <- paymentService.listPayments(providerId, bookingId)
          summary   <- paymentService.getPaymentSummary(providerId, bookingId)
        yield Right(ListPaymentsResponse(
          payments = payments.map(toPaymentResponse),
          summary  = toSummaryResponse(summary)
        )))
          .handleErrorWith {
            case _: PaymentError.BookingNotFound => Left(notFound("Booking not found")).pure[F]
            case _: PaymentError.Forbidden       => Left(forbidden("Access denied")).pure[F]
            case e: PaymentError                 => Left(badRequest(e.getMessage)).pure[F]
            case _                               => Left((StatusCode.InternalServerError, ErrorBody("Internal error"))).pure[F]
          }
      }

    Http4sServerInterpreter[F]().toRoutes(List(recordServer, listServer))
