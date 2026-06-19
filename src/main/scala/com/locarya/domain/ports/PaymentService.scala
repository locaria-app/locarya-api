package com.locarya.domain.ports

import com.locarya.domain.models.*

final case class PaymentSummary(
  total:      BigDecimal,
  paid:       BigDecimal,
  balanceDue: BigDecimal
)

trait PaymentService[F[_]]:
  def recordPayment(
    providerId: ProviderId,
    bookingId:  BookingId,
    amount:     BigDecimal,
    method:     PaymentMethod,
    note:       Option[String]
  ): F[Payment]

  def listPayments(providerId: ProviderId, bookingId: BookingId): F[List[Payment]]

  def getPaymentSummary(providerId: ProviderId, bookingId: BookingId): F[PaymentSummary]
