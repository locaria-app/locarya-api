package com.locarya.domain.models

import java.time.Instant

enum PaymentMethod:
  case PixManual, PixAsaas

object PaymentMethod:
  def encode(m: PaymentMethod): String = m match
    case PixManual => "pix_manual"
    case PixAsaas  => "pix_asaas"

  def decode(s: String): Either[InvalidPayment, PaymentMethod] = s match
    case "pix_manual" => Right(PixManual)
    case "pix_asaas"  => Right(PixAsaas)
    case other        => Left(InvalidPayment(s"Unknown payment method: $other"))

enum PaymentStatus:
  case Confirmed

final case class Payment private (
  id:        PaymentId,
  bookingId: BookingId,
  amount:    Money,
  method:    PaymentMethod,
  status:    PaymentStatus,
  note:      Option[String],
  paidAt:    Instant
)

object Payment:
  def create(
    id:        PaymentId,
    bookingId: BookingId,
    amount:    BigDecimal,
    method:    PaymentMethod,
    note:      Option[String],
    paidAt:    Instant
  ): Either[ValidationError, Payment] =
    Money.fromAmount(amount).map { money =>
      Payment(id, bookingId, money, method, PaymentStatus.Confirmed, note, paidAt)
    }
