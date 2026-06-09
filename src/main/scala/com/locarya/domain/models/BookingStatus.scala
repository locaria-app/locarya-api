package com.locarya.domain.models

sealed trait BookingStatus {
  def transitionTo(newStatus: BookingStatus): Either[ValidationError, BookingStatus] = {
    (this, newStatus) match {
      // From Pending
      case (BookingStatus.Pending, BookingStatus.Confirmed) => Right(BookingStatus.Confirmed)
      case (BookingStatus.Pending, BookingStatus.Cancelled) => Right(BookingStatus.Cancelled)

      // From Confirmed
      case (BookingStatus.Confirmed, BookingStatus.InProgress) => Right(BookingStatus.InProgress)
      case (BookingStatus.Confirmed, BookingStatus.Cancelled) => Right(BookingStatus.Cancelled)

      // From InProgress
      case (BookingStatus.InProgress, BookingStatus.Completed) => Right(BookingStatus.Completed)
      case (BookingStatus.InProgress, BookingStatus.Cancelled) => Right(BookingStatus.Cancelled)

      // Terminal states cannot transition
      case (BookingStatus.Completed, _) =>
        Left(InvalidStatusTransition(s"Cannot transition from Completed to $newStatus"))
      case (BookingStatus.Cancelled, _) =>
        Left(InvalidStatusTransition(s"Cannot transition from Cancelled to $newStatus"))

      // All other transitions are invalid
      case (current, target) =>
        Left(InvalidStatusTransition(s"Invalid transition from $current to $target"))
    }
  }
}

object BookingStatus {
  case object Pending extends BookingStatus
  case object Confirmed extends BookingStatus
  case object InProgress extends BookingStatus
  case object Completed extends BookingStatus
  case object Cancelled extends BookingStatus
}
