package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.middleware.AuthMiddleware
import com.locarya.domain.models.*
import com.locarya.domain.ports.{PaymentService, PaymentSummary}
import io.circe.Encoder
import io.circe.generic.auto.*
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl

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
  private given Encoder[PaymentResponse] = deriveEncoder

  private case class SummaryResponse(
    total:      BigDecimal,
    paid:       BigDecimal,
    balanceDue: BigDecimal
  )
  private given Encoder[SummaryResponse] = deriveEncoder

  private case class ListPaymentsResponse(
    payments: List[PaymentResponse],
    summary:  SummaryResponse
  )
  private given Encoder[ListPaymentsResponse] = deriveEncoder

  private case class ErrorResponseBody(error: String)
  private given Encoder[ErrorResponseBody] = deriveEncoder

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

  def routes[F[_]: Async](
    paymentService: PaymentService[F],
    jwtSecret:      String
  ): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*

    given EntityDecoder[F, RecordPaymentBody] = jsonOf[F, RecordPaymentBody]

    AuthMiddleware.withProviderId[F](jwtSecret) { rawProviderId =>
      val providerIdF: F[ProviderId] =
        ProviderId.fromString(rawProviderId)
          .fold(err => PaymentError.InvalidInput(err).raiseError[F, ProviderId], _.pure[F])

      HttpRoutes.of[F]:

        case req @ POST -> Root / "dashboard" / "bookings" / bookingIdStr / "payments" =>
          req.as[RecordPaymentBody].flatMap { body =>
            (for
              pid       <- providerIdF
              bookingId <- BookingId.fromString(bookingIdStr)
                             .fold(err => PaymentError.InvalidInput(err).raiseError[F, BookingId], _.pure[F])
              method    <- PaymentMethod.decode(body.method)
                             .fold(err => PaymentError.InvalidInput(err).raiseError[F, PaymentMethod], _.pure[F])
              payment   <- paymentService.recordPayment(pid, bookingId, body.amount, method, body.note)
              resp      <- Created(toPaymentResponse(payment).asJson)
            yield resp).handleErrorWith {
              case _: PaymentError.BookingNotFound     => NotFound(ErrorResponseBody("Booking not found").asJson)
              case _: PaymentError.Forbidden           => Forbidden(ErrorResponseBody("Access denied").asJson)
              case e: PaymentError.InvalidInput        => BadRequest(ErrorResponseBody(e.getMessage).asJson)
              case _: MalformedMessageBodyFailure      => BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
              case _: InvalidMessageBodyFailure        => BadRequest(ErrorResponseBody("Invalid request body").asJson)
            }
          }.handleErrorWith {
            case _: MalformedMessageBodyFailure => BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
            case _: InvalidMessageBodyFailure   => BadRequest(ErrorResponseBody("Invalid request body").asJson)
          }

        case GET -> Root / "dashboard" / "bookings" / bookingIdStr / "payments" =>
          (for
            pid       <- providerIdF
            bookingId <- BookingId.fromString(bookingIdStr)
                           .fold(err => PaymentError.InvalidInput(err).raiseError[F, BookingId], _.pure[F])
            payments  <- paymentService.listPayments(pid, bookingId)
            summary   <- paymentService.getPaymentSummary(pid, bookingId)
            resp      <- Ok(
                           ListPaymentsResponse(
                             payments = payments.map(toPaymentResponse),
                             summary  = toSummaryResponse(summary)
                           ).asJson
                         )
          yield resp).handleErrorWith {
            case _: PaymentError.BookingNotFound => NotFound(ErrorResponseBody("Booking not found").asJson)
            case _: PaymentError.Forbidden       => Forbidden(ErrorResponseBody("Access denied").asJson)
            case e: PaymentError                 => BadRequest(ErrorResponseBody(e.getMessage).asJson)
            case _                               => InternalServerError(ErrorResponseBody("Internal error").asJson)
          }
    }
