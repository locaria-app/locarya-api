package com.locarya.core.domain

final case class Money private (amount: BigDecimal) {
  def +(other: Money): Money = Money(amount + other.amount)

  def -(other: Money): Either[ValidationError, Money] = {
    val newAmount = amount - other.amount
    if (newAmount >= 0) Right(Money(newAmount))
    else Left(InvalidAmount(s"Subtraction would result in negative amount: $newAmount"))
  }

  def *(multiplier: BigDecimal): Either[ValidationError, Money] = {
    if (multiplier > 0) Right(Money(amount * multiplier))
    else Left(InvalidAmount(s"Multiplier must be positive, got: $multiplier"))
  }
}

object Money {
  def fromAmount(amount: BigDecimal): Either[ValidationError, Money] =
    if (amount > 0) Right(Money(amount))
    else Left(InvalidAmount(s"Amount must be positive, got: $amount"))
}
