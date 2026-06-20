package com.locarya.domain.models

import java.time.LocalDate

final case class Subscription private (
  id:         SubscriptionId,
  providerId: ProviderId,
  planId:     PlanId,
  status:     SubscriptionStatus,
  startDate:  LocalDate,
  endDate:    Option[LocalDate]
) {
  def isActiveOn(date: LocalDate): Boolean =
    status == SubscriptionStatus.Active &&
    !date.isBefore(startDate) &&
    endDate.forall(end => !date.isAfter(end))
}

object Subscription:
  def create(
    id:         SubscriptionId,
    providerId: ProviderId,
    planId:     PlanId,
    status:     SubscriptionStatus,
    startDate:  LocalDate,
    endDate:    Option[LocalDate]
  ): Either[ValidationError, Subscription] =
    endDate match
      case Some(end) if !end.isAfter(startDate) =>
        Left(InvalidSubscription("endDate must be strictly after startDate"))
      case _ =>
        Right(Subscription(id, providerId, planId, status, startDate, endDate))
