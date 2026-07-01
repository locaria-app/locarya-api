package com.locarya.domain.services

import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import java.time.Instant
import org.typelevel.log4cats.Logger

class BookingChargeServiceImpl[F[_]: Sync: Logger](
  providerRepo: ProviderRepository[F],
  bookingRepo:  BookingRepository[F],
  customerRepo: CustomerRepository[F],
  chargeRepo:   BookingChargeRepository[F],
  gateway:      PaymentGateway[F],
  notifRepo:    NotificationEventRepository[F]
) extends BookingChargeService[F]:

  def chargeBooking(slug: StorefrontSlug, bookingId: BookingId): F[ChargeOutcome] =
    for
      provider <- requireProvider(slug)
      _        <- requireOnlinePaymentEnabled(provider)
      booking  <- requireBookingBelongsTo(bookingId, provider.id)
      outcome  <- fetchOrCreateCharge(booking, provider)
    yield outcome

  private def requireProvider(slug: StorefrontSlug): F[Provider] =
    providerRepo.findBySlug(slug).flatMap {
      case Some(p) => p.pure[F]
      case None    => BookingChargeError.NotFound(s"Storefront '${slug.value}' not found").raiseError[F, Provider]
    }

  private def requireOnlinePaymentEnabled(provider: Provider): F[Unit] =
    val enabled = provider.planTier == PlanTier.Premium && provider.walletId.isDefined
    if enabled then ().pure[F]
    else BookingChargeError.OnlinePaymentNotEnabled(provider.id).raiseError[F, Unit]

  private def requireBookingBelongsTo(bookingId: BookingId, providerId: ProviderId): F[Booking] =
    bookingRepo.findById(bookingId).flatMap {
      case Some(b) if b.providerId == providerId => b.pure[F]
      case _                                     =>
        BookingChargeError.NotFound(s"Booking '${bookingId.value}' not found in this storefront")
          .raiseError[F, Booking]
    }

  private def fetchOrCreateCharge(booking: Booking, provider: Provider): F[ChargeOutcome] =
    chargeRepo.findPendingByBooking(booking.id).flatMap {
      case Some(existing) =>
        ChargeOutcome.ExistingPending(existing.paymentUrl).pure[F]
      case None =>
        createNewCharge(booking, provider)
    }

  private def createNewCharge(booking: Booking, provider: Provider): F[ChargeOutcome] =
    for
      customer   <- customerRepo.findById(booking.customerId).flatMap {
                      case Some(c) => c.pure[F]
                      case None    =>
                        BookingChargeError.NotFound(s"Customer '${booking.customerId.value}' not found")
                          .raiseError[F, Customer]
                    }
      walletId    = provider.walletId.get
      asaasCharge <- gateway.createCharge(
                       booking.id,
                       walletId,
                       booking.totalAmount.amount,
                       customer.email.value
                     )
      charge      <- liftValidation(
                       BookingCharge.create(
                         id         = BookingChargeId.generate,
                         bookingId  = booking.id,
                         chargeId   = asaasCharge.chargeId,
                         paymentUrl = asaasCharge.paymentUrl,
                         createdAt  = Instant.now()
                       )
                     )
      _           <- chargeRepo.create(charge)
      _           <- Logger[F].info(chargeCreatedLog(charge, provider))
      notifPayload = s"""{"bookingId":"${booking.id.value}","paymentUrl":"${asaasCharge.paymentUrl}"}"""
      notifEvent   = NotificationEvent.create(NotificationEventId.generate, "BookingCreatedWithPaymentLink", notifPayload, Instant.now())
      _           <- notifRepo.create(notifEvent)
    yield ChargeOutcome.Created(charge.paymentUrl)

  private def liftValidation[A](e: Either[ValidationError, A]): F[A] =
    e.fold(err => BookingChargeError.InvalidInput(err).raiseError[F, A], _.pure[F])

  private def chargeCreatedLog(charge: BookingCharge, provider: Provider): String =
    s"""{"event":"BookingChargeCreated","bookingId":"${charge.bookingId.value}","chargeId":"${charge.chargeId}","providerId":"${provider.id.value}"}"""
