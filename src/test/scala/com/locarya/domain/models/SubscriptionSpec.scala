package com.locarya.domain.models

import munit.FunSuite
import java.time.LocalDate

class SubscriptionSpec extends FunSuite {

  // ── SubscriptionId ──────────────────────────────────────────────────────────

  test("SubscriptionId.generate returns distinct UUIDs") {
    val id1 = SubscriptionId.generate
    val id2 = SubscriptionId.generate
    assert(id1 != id2, "generate should return distinct IDs")
  }

  test("SubscriptionId.fromString with valid UUID returns Right") {
    val uuid   = java.util.UUID.randomUUID().toString
    val result = SubscriptionId.fromString(uuid)
    assert(result.isRight)
    result.foreach { id =>
      assertEquals(id.value, uuid)
    }
  }

  test("SubscriptionId.fromString with non-UUID string returns Left(InvalidEntityId)") {
    val result = SubscriptionId.fromString("not-a-uuid")
    assert(result.isLeft)
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidEntityId])
    }
  }

  // ── Subscription.create ─────────────────────────────────────────────────────

  private def makeSubscription(
    endDate: Option[LocalDate],
    startDate: LocalDate = LocalDate.of(2024, 1, 1),
    status: SubscriptionStatus = SubscriptionStatus.Active
  ) = Subscription.create(
    id         = SubscriptionId.generate,
    providerId = ProviderId.generate,
    planId     = PlanId.generate,
    status     = status,
    startDate  = startDate,
    endDate    = endDate
  )

  test("Subscription.create with endDate = None returns Right") {
    val result = makeSubscription(endDate = None)
    assert(result.isRight)
  }

  test("Subscription.create with endDate strictly after startDate returns Right") {
    val result = makeSubscription(endDate = Some(LocalDate.of(2024, 12, 31)))
    assert(result.isRight)
  }

  test("Subscription.create with endDate equal to startDate returns Left(InvalidSubscription)") {
    val start  = LocalDate.of(2024, 1, 1)
    val result = makeSubscription(endDate = Some(start), startDate = start)
    assert(result.isLeft)
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidSubscription])
    }
  }

  test("Subscription.create with endDate before startDate returns Left(InvalidSubscription)") {
    val result = makeSubscription(
      startDate = LocalDate.of(2024, 6, 1),
      endDate   = Some(LocalDate.of(2024, 1, 1))
    )
    assert(result.isLeft)
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidSubscription])
    }
  }

  // ── Subscription.isActiveOn ─────────────────────────────────────────────────

  private val start = LocalDate.of(2024, 1, 1)

  private def activeOpenSubscription: Subscription =
    makeSubscription(endDate = None, startDate = start).toOption.get

  private def activeClosedSubscription(end: LocalDate): Subscription =
    makeSubscription(
      startDate = start,
      endDate   = Some(end)
    ).toOption.get

  private def suspendedSubscription: Subscription =
    makeSubscription(
      endDate = None,
      startDate = start,
      status = SubscriptionStatus.Suspended
    ).toOption.get

  private def cancelledSubscription: Subscription =
    makeSubscription(
      endDate = None,
      startDate = start,
      status = SubscriptionStatus.Cancelled
    ).toOption.get

  test("isActiveOn: Active, open-ended, date = startDate → true") {
    assert(activeOpenSubscription.isActiveOn(start))
  }

  test("isActiveOn: Active, open-ended, date after startDate → true") {
    assert(activeOpenSubscription.isActiveOn(start.plusYears(1)))
  }

  test("isActiveOn: Active, open-ended, date before startDate → false") {
    assert(!activeOpenSubscription.isActiveOn(start.minusDays(1)))
  }

  test("isActiveOn: Active, bounded, date within range → true") {
    val end = LocalDate.of(2024, 12, 31)
    assert(activeClosedSubscription(end).isActiveOn(LocalDate.of(2024, 6, 15)))
  }

  test("isActiveOn: Active, bounded, date = endDate → true (inclusive)") {
    val end = LocalDate.of(2024, 12, 31)
    assert(activeClosedSubscription(end).isActiveOn(end))
  }

  test("isActiveOn: Active, bounded, date after endDate → false") {
    val end = LocalDate.of(2024, 12, 31)
    assert(!activeClosedSubscription(end).isActiveOn(end.plusDays(1)))
  }

  test("isActiveOn: Suspended, any date in range → false") {
    assert(!suspendedSubscription.isActiveOn(start.plusDays(10)))
  }

  test("isActiveOn: Cancelled, any date in range → false") {
    assert(!cancelledSubscription.isActiveOn(start.plusDays(10)))
  }
}
