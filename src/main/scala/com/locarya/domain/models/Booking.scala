package com.locarya.domain.models

import java.time.LocalDate

final case class PartyProfile(
  kidsCount:  Option[Int]          = None,
  ageGroups:  Option[List[String]] = None,
  venueType:  Option[String]       = None
)

sealed trait BookingItem

/** A booked line. `unitPrice` is the price captured at booking-creation time (the
  * price snapshot persisted in `booking_items.unit_price`). It is `None` for bookings
  * created before snapshots were recorded; storefront-created bookings always carry it.
  */
final case class BookedIndividualItem(itemId: ItemId, quantity: Int, unitPrice: Option[Money] = None) extends BookingItem
final case class BookedCombo(comboId: ComboId, quantity: Int, unitPrice: Option[Money] = None) extends BookingItem

/** Who created the Booking. A Customer creating via the Storefront yields a `Pending`
  * booking awaiting the Provider's confirmation; a Provider creating via the Dashboard
  * starts the booking already confirmed (see CONTEXT.md booking flows).
  */
enum BookingCreator:
  case Customer, Provider

final case class Booking private (
  id: BookingId,
  providerId: ProviderId,
  customerId: CustomerId,
  items: List[BookingItem],
  startDate: LocalDate,
  endDate: LocalDate,
  totalAmount: Money,
  status: BookingStatus,
  deliveryAddress: Option[Address],
  createdBy: BookingCreator,
  bookingCode: BookingCode,
  partyProfile: Option[PartyProfile] = None,
  confirmedWithoutMonitor: Boolean = false
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
    deliveryAddress: Option[Address] = None,
    createdBy: BookingCreator = BookingCreator.Provider,
    bookingCode: BookingCode = BookingCode.generate,
    partyProfile: Option[PartyProfile] = None
  ): Either[ValidationError, Booking] = {
    if (items.isEmpty) {
      Left(InvalidBooking("Booking must contain at least one item"))
    } else if (startDate.isAfter(endDate)) {
      Left(InvalidBooking("Start date cannot be after end date"))
    } else if (items.exists {
      case BookedIndividualItem(_, qty, _) => qty <= 0
      case BookedCombo(_, qty, _) => qty <= 0
    }) {
      Left(InvalidBooking("All item quantities must be positive"))
    } else {
      Right(Booking(id, providerId, customerId, items, startDate, endDate, totalAmount, status, deliveryAddress, createdBy, bookingCode, partyProfile))
    }
  }

  extension (booking: Booking) {
    def transitionStatus(newStatus: BookingStatus): Either[ValidationError, Booking] = {
      booking.status.transitionTo(newStatus).map { validatedStatus =>
        booking.copy(status = validatedStatus)
      }
    }

    def markConfirmedWithoutMonitor: Booking = booking.copy(confirmedWithoutMonitor = true)
  }
}
