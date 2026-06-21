package com.locarya.domain.models

sealed trait SubscriptionStatus {
  def transitionTo(newStatus: SubscriptionStatus): Either[ValidationError, SubscriptionStatus] = {
    (this, newStatus) match {
      // From Active
      case (SubscriptionStatus.Active, SubscriptionStatus.Active)    => Right(SubscriptionStatus.Active)
      case (SubscriptionStatus.Active, SubscriptionStatus.Suspended) => Right(SubscriptionStatus.Suspended)
      case (SubscriptionStatus.Active, SubscriptionStatus.Cancelled) => Right(SubscriptionStatus.Cancelled)

      // From Suspended
      case (SubscriptionStatus.Suspended, SubscriptionStatus.Suspended) => Right(SubscriptionStatus.Suspended)
      case (SubscriptionStatus.Suspended, SubscriptionStatus.Active)    => Right(SubscriptionStatus.Active)
      case (SubscriptionStatus.Suspended, SubscriptionStatus.Cancelled) => Right(SubscriptionStatus.Cancelled)

      // From Cancelled (terminal state - no transitions allowed)
      case (SubscriptionStatus.Cancelled, _) =>
        Left(InvalidStatusTransition(s"Cannot transition from Cancelled to $newStatus"))
    }
  }
}

object SubscriptionStatus {
  case object Active    extends SubscriptionStatus
  case object Suspended extends SubscriptionStatus
  case object Cancelled extends SubscriptionStatus
}
