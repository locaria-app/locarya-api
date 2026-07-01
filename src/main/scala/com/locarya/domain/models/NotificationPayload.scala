package com.locarya.domain.models

sealed trait NotificationPayload

object NotificationPayload:
  final case class BookingCreatedWithPaymentLink(
    booking:    Booking,
    customer:   Customer,
    provider:   Provider,
    paymentUrl: String
  ) extends NotificationPayload

  final case class PaymentConfirmed(
    booking:  Booking,
    customer: Customer,
    provider: Provider,
    amount:   Money
  ) extends NotificationPayload

  final case class BookingStatusChanged(
    booking:        Booking,
    customer:       Customer,
    provider:       Provider,
    previousStatus: BookingStatus,
    newStatus:      BookingStatus
  ) extends NotificationPayload
