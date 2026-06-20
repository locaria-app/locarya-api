package com.locarya.domain.models

sealed trait PlanTier
object PlanTier:
  case object Freemium extends PlanTier
  case object Premium  extends PlanTier

final case class Plan private (
  id:                    PlanId,
  tier:                  PlanTier,
  monthlyFee:            BigDecimal,
  transactionFeePercent: BigDecimal,
  maxItems:              Int,
  maxBookingsPerMonth:   Int
)

object Plan:
  def create(
    id:                    PlanId,
    tier:                  PlanTier,
    monthlyFee:            BigDecimal,
    transactionFeePercent: BigDecimal,
    maxItems:              Int,
    maxBookingsPerMonth:   Int
  ): Either[ValidationError, Plan] =
    if monthlyFee < 0 then
      Left(InvalidPlan("monthlyFee must be non-negative"))
    else if transactionFeePercent < 0 || transactionFeePercent > 100 then
      Left(InvalidPlan("transactionFeePercent must be between 0 and 100"))
    else
      Right(Plan(id, tier, monthlyFee, transactionFeePercent, maxItems, maxBookingsPerMonth))
