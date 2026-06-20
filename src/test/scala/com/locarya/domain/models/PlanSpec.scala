package com.locarya.domain.models

import munit.FunSuite

class PlanSpec extends FunSuite:

  // === PlanTier ADT ===

  test("PlanTier Freemium and Premium are distinct") {
    assertNotEquals(PlanTier.Freemium: PlanTier, PlanTier.Premium: PlanTier)
  }

  test("PlanTier variants are exhaustively matchable") {
    def label(t: PlanTier): String = t match
      case PlanTier.Freemium => "freemium"
      case PlanTier.Premium  => "premium"
    assertEquals(label(PlanTier.Freemium), "freemium")
    assertEquals(label(PlanTier.Premium), "premium")
  }

  // === Plan.create — happy path ===

  test("Plan.create with valid Freemium config returns Right with expected fields") {
    val id = PlanId.generate
    val result = Plan.create(
      id                    = id,
      tier                  = PlanTier.Freemium,
      monthlyFee            = BigDecimal(0),
      transactionFeePercent = BigDecimal(2.5),
      maxItems              = 10,
      maxBookingsPerMonth   = 50
    )
    assert(result.isRight)
    val plan = result.toOption.get
    assertEquals(plan.id, id)
    assertEquals(plan.tier, PlanTier.Freemium)
    assertEquals(plan.monthlyFee, BigDecimal(0))
    assertEquals(plan.transactionFeePercent, BigDecimal(2.5))
    assertEquals(plan.maxItems, 10)
    assertEquals(plan.maxBookingsPerMonth, 50)
  }

  test("Plan.create with valid Premium config returns Right") {
    val result = Plan.create(
      id                    = PlanId.generate,
      tier                  = PlanTier.Premium,
      monthlyFee            = BigDecimal("99.90"),
      transactionFeePercent = BigDecimal("1.50"),
      maxItems              = 100,
      maxBookingsPerMonth   = 500
    )
    assert(result.isRight)
    assertEquals(result.toOption.get.tier, PlanTier.Premium)
  }

  // === Validation rejections ===

  test("Plan.create with negative monthlyFee returns Left(InvalidPlan)") {
    val result = Plan.create(
      id                    = PlanId.generate,
      tier                  = PlanTier.Freemium,
      monthlyFee            = BigDecimal(-1),
      transactionFeePercent = BigDecimal(0),
      maxItems              = 10,
      maxBookingsPerMonth   = 10
    )
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[InvalidPlan])
  }

  test("Plan.create with transactionFeePercent below 0 returns Left(InvalidPlan)") {
    val result = Plan.create(
      id                    = PlanId.generate,
      tier                  = PlanTier.Freemium,
      monthlyFee            = BigDecimal(0),
      transactionFeePercent = BigDecimal("-0.01"),
      maxItems              = 10,
      maxBookingsPerMonth   = 10
    )
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[InvalidPlan])
  }

  test("Plan.create with transactionFeePercent above 100 returns Left(InvalidPlan)") {
    val result = Plan.create(
      id                    = PlanId.generate,
      tier                  = PlanTier.Freemium,
      monthlyFee            = BigDecimal(0),
      transactionFeePercent = BigDecimal("100.01"),
      maxItems              = 10,
      maxBookingsPerMonth   = 10
    )
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[InvalidPlan])
  }

  // === Boundary values ===

  test("Plan.create with monthlyFee = 0 is accepted") {
    val result = Plan.create(
      id                    = PlanId.generate,
      tier                  = PlanTier.Freemium,
      monthlyFee            = BigDecimal(0),
      transactionFeePercent = BigDecimal(0),
      maxItems              = 10,
      maxBookingsPerMonth   = 10
    )
    assert(result.isRight)
  }

  test("Plan.create with transactionFeePercent = 100 is accepted") {
    val result = Plan.create(
      id                    = PlanId.generate,
      tier                  = PlanTier.Freemium,
      monthlyFee            = BigDecimal(0),
      transactionFeePercent = BigDecimal(100),
      maxItems              = 10,
      maxBookingsPerMonth   = 10
    )
    assert(result.isRight)
  }
