package com.locarya.domain.ports

import com.locarya.domain.models.*

sealed trait ChargeOutcome:
  def paymentUrl: String

object ChargeOutcome:
  final case class Created(paymentUrl: String)         extends ChargeOutcome
  final case class ExistingPending(paymentUrl: String) extends ChargeOutcome

trait BookingChargeService[F[_]]:
  def chargeBooking(slug: StorefrontSlug, bookingId: BookingId): F[ChargeOutcome]
