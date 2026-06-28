package com.locarya.domain.ports

import com.locarya.domain.models.{AsaasCharge, BookingId}

trait PaymentGateway[F[_]]:
  def createCharge(bookingId: BookingId, walletId: String, amount: BigDecimal, customerEmail: String): F[AsaasCharge]
  def cancelCharge(chargeId: String): F[Unit]
