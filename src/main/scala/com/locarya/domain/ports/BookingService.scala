package com.locarya.domain.ports

import com.locarya.domain.models.*
import java.time.LocalDate
import com.locarya.domain.models.BookingLineRef

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
  startTime:       Option[String]      = None,
  endTime:         Option[String]      = None,
  partyProfile:    Option[PartyProfile] = None
)

/** The result of a successful booking creation. */
final case class BookingCreated(
  bookingId:   BookingId,
  status:      BookingStatus,
  totalAmount: Money,
  bookingCode: BookingCode
)

/** A customer view for the dashboard: includes denormalized customer details. */
final case class DashboardBookingView(
  id:               BookingId,
  providerId:       ProviderId,
  customer:         DashboardCustomerView,
  items:            List[BookingItem],
  date:             LocalDate,
  deliveryAddress:  Option[Address],
  status:           BookingStatus,
  totalAmount:      Money,
  createdBy:        BookingCreator,
  bookingCode:      BookingCode
)

final case class DashboardCustomerView(
  name:  String,
  email: String,
  phone: Option[String]
)

/** Request DTO for provider-created bookings. */
final case class CreateBookingByProviderRequest(
  items:           List[BookingLineInput],
  date:            LocalDate,
  deliveryAddress: Address,
  customer:        CustomerInput
)

final case class BookingLineAttendants(lineRef: BookingLineRef, attendants: List[Attendant])

final case class DashboardBookingDetailView(
  id:                 BookingId,
  providerId:         ProviderId,
  customer:           DashboardCustomerView,
  items:              List[BookingItem],
  date:               LocalDate,
  deliveryAddress:    Option[Address],
  status:             BookingStatus,
  totalAmount:        Money,
  createdBy:          BookingCreator,
  bookingCode:        BookingCode,
  assignedAttendants: List[BookingLineAttendants]
)

trait BookingService[F[_]]:
  def createBooking(request: CreateBookingRequest): F[BookingCreated]
  def createBookingByProvider(providerId: ProviderId, request: CreateBookingByProviderRequest): F[BookingCreated]
  def listBookings(providerId: ProviderId, status: Option[BookingStatus], dateFrom: Option[LocalDate], dateTo: Option[LocalDate]): F[List[DashboardBookingView]]
  def updateBookingStatus(providerId: ProviderId, bookingId: BookingId, newStatus: BookingStatus, reason: Option[String], overrideMonitorCheck: Boolean = false): F[Booking]
  def getBookingDetail(providerId: ProviderId, bookingId: BookingId): F[DashboardBookingDetailView]
