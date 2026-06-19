package com.locarya.domain.services

import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import java.time.Instant
import org.typelevel.log4cats.Logger

class PaymentServiceImpl[F[_]: Sync: Logger](
  bookingRepo: BookingRepository[F],
  paymentRepo: PaymentRepository[F]
) extends PaymentService[F]:

  def recordPayment(
    providerId: ProviderId,
    bookingId:  BookingId,
    amount:     BigDecimal,
    method:     PaymentMethod,
    note:       Option[String]
  ): F[Payment] =
    for
      booking <- requireBookingOwned(bookingId, providerId)
      payment <- liftValidation(
                   Payment.create(
                     id        = PaymentId.generate,
                     bookingId = booking.id,
                     amount    = amount,
                     method    = method,
                     note      = note,
                     paidAt    = Instant.now()
                   )
                 )
      stored  <- paymentRepo.create(payment)
      _       <- Logger[F].info(paymentRecordedLog(stored))
    yield stored

  def listPayments(providerId: ProviderId, bookingId: BookingId): F[List[Payment]] =
    for
      _ <- requireBookingOwned(bookingId, providerId)
      p <- paymentRepo.findByBooking(bookingId)
    yield p

  def getPaymentSummary(providerId: ProviderId, bookingId: BookingId): F[PaymentSummary] =
    for
      booking  <- requireBookingOwned(bookingId, providerId)
      payments <- paymentRepo.findByBooking(bookingId)
      paid      = payments.map(_.amount.amount).sum
      total     = booking.totalAmount.amount
    yield PaymentSummary(total = total, paid = paid, balanceDue = total - paid)

  private def requireBookingOwned(bookingId: BookingId, providerId: ProviderId): F[Booking] =
    bookingRepo.findById(bookingId).flatMap {
      case None                                              => PaymentError.BookingNotFound(bookingId).raiseError[F, Booking]
      case Some(b) if b.providerId != providerId             => PaymentError.Forbidden(bookingId).raiseError[F, Booking]
      case Some(b)                                           => b.pure[F]
    }

  private def liftValidation[A](e: Either[ValidationError, A]): F[A] =
    e.fold(err => PaymentError.InvalidInput(err).raiseError[F, A], _.pure[F])

  private def paymentRecordedLog(payment: Payment): String =
    s"""{"event":"PaymentRecorded","bookingId":"${payment.bookingId.value}","paymentId":"${payment.id.value}","amount":${payment.amount.amount},"method":"${PaymentMethod.encode(payment.method)}"}"""
