package com.locarya.domain.ports

import com.locarya.domain.models.*
import java.time.LocalDate

/** A customer's contact details as collected by the Storefront booking form. The
  * Storefront does not collect a CPF, so `cpf` is absent here.
  */
final case class CustomerInput(
  name:  String,
  email: Email,
  phone: Option[String]
)

/** One requested line of a booking. `itemId` may reference an Item or a Combo — the
  * Combo's id is reused as an [[ItemId]], mirroring the availability engine.
  */
final case class BookingLineInput(
  itemId:   ItemId,
  quantity: Int
)

final case class CreateBookingRequest(
  slug:            StorefrontSlug,
  items:           List[BookingLineInput],
  date:            LocalDate,
  deliveryAddress: Address,
  customer:        CustomerInput,
  startTime:       Option[String] = None,
  endTime:         Option[String] = None
)

/** The result of a successful booking creation. */
final case class BookingCreated(
  bookingId:   BookingId,
  status:      BookingStatus,
  totalAmount: Money
)

trait BookingService[F[_]]:
  def createBooking(request: CreateBookingRequest): F[BookingCreated]
