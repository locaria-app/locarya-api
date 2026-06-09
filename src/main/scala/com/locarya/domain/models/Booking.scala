package com.locarya.domain.models

import java.time.LocalDate

sealed trait BookingItem
final case class BookedIndividualItem(itemId: ItemId, quantity: Int) extends BookingItem
final case class BookedCombo(comboId: ComboId, quantity: Int) extends BookingItem

final case class Booking private (
  id: BookingId,
  providerId: ProviderId,
  customerId: CustomerId,
  items: List[BookingItem],
  startDate: LocalDate,
  endDate: LocalDate,
  totalAmount: Money,
  status: BookingStatus,
  attendantId: Option[AttendantId],
  deliveryAddress: Option[Address]
)

object Booking {
  def create(
    id: BookingId,
    providerId: ProviderId,
    customerId: CustomerId,
    items: List[BookingItem],
    startDate: LocalDate,
    endDate: LocalDate,
    totalAmount: Money,
    status: BookingStatus = BookingStatus.Pending,
    attendantId: Option[AttendantId] = None,
    deliveryAddress: Option[Address] = None
  ): Either[ValidationError, Booking] = {
    if (items.isEmpty) {
      Left(InvalidBooking("Booking must contain at least one item"))
    } else if (startDate.isAfter(endDate)) {
      Left(InvalidBooking("Start date cannot be after end date"))
    } else if (items.exists {
      case BookedIndividualItem(_, qty) => qty <= 0
      case BookedCombo(_, qty) => qty <= 0
    }) {
      Left(InvalidBooking("All item quantities must be positive"))
    } else {
      Right(Booking(id, providerId, customerId, items, startDate, endDate, totalAmount, status, attendantId, deliveryAddress))
    }
  }

  extension (booking: Booking) {
    def transitionStatus(newStatus: BookingStatus): Either[ValidationError, Booking] = {
      booking.status.transitionTo(newStatus).map { validatedStatus =>
        booking.copy(status = validatedStatus)
      }
    }

    def assignAttendant(attendantId: AttendantId): Booking = {
      booking.copy(attendantId = Some(attendantId))
    }
  }
}
