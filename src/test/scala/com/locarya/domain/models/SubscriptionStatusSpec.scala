package com.locarya.domain.models

import munit.FunSuite

class SubscriptionStatusSpec extends FunSuite {

  test("SubscriptionStatus.Active exists") {
    val status: SubscriptionStatus = SubscriptionStatus.Active
    assert(status == SubscriptionStatus.Active)
  }

  test("SubscriptionStatus.Suspended exists") {
    val status: SubscriptionStatus = SubscriptionStatus.Suspended
    assert(status == SubscriptionStatus.Suspended)
  }

  test("SubscriptionStatus.Cancelled exists") {
    val status: SubscriptionStatus = SubscriptionStatus.Cancelled
    assert(status == SubscriptionStatus.Cancelled)
  }

  test("transition from Active to Suspended succeeds") {
    val result = SubscriptionStatus.Active.transitionTo(SubscriptionStatus.Suspended)

    assert(result.isRight, "Should allow Active -> Suspended transition")
    result.foreach { newStatus =>
      assertEquals(newStatus, SubscriptionStatus.Suspended)
    }
  }

  test("transition from Active to Cancelled succeeds") {
    val result = SubscriptionStatus.Active.transitionTo(SubscriptionStatus.Cancelled)

    assert(result.isRight, "Should allow Active -> Cancelled transition")
    result.foreach { newStatus =>
      assertEquals(newStatus, SubscriptionStatus.Cancelled)
    }
  }

  test("transition from Suspended to Active succeeds") {
    val result = SubscriptionStatus.Suspended.transitionTo(SubscriptionStatus.Active)

    assert(result.isRight, "Should allow Suspended -> Active transition")
    result.foreach { newStatus =>
      assertEquals(newStatus, SubscriptionStatus.Active)
    }
  }

  test("transition from Suspended to Cancelled succeeds") {
    val result = SubscriptionStatus.Suspended.transitionTo(SubscriptionStatus.Cancelled)

    assert(result.isRight, "Should allow Suspended -> Cancelled transition")
    result.foreach { newStatus =>
      assertEquals(newStatus, SubscriptionStatus.Cancelled)
    }
  }

  test("transition from Cancelled to any status fails") {
    val resultToActive    = SubscriptionStatus.Cancelled.transitionTo(SubscriptionStatus.Active)
    val resultToSuspended = SubscriptionStatus.Cancelled.transitionTo(SubscriptionStatus.Suspended)

    assert(resultToActive.isLeft, "Should not allow Cancelled -> Active transition")
    assert(resultToSuspended.isLeft, "Should not allow Cancelled -> Suspended transition")

    resultToActive.left.foreach { error =>
      assert(error.isInstanceOf[InvalidStatusTransition], "Should return InvalidStatusTransition error")
    }
  }

  test("transition from Active to Active is a no-op") {
    val result = SubscriptionStatus.Active.transitionTo(SubscriptionStatus.Active)

    assert(result.isRight, "Should allow Active -> Active (no-op)")
    result.foreach { newStatus =>
      assertEquals(newStatus, SubscriptionStatus.Active)
    }
  }

  test("transition from Suspended to Suspended is a no-op") {
    val result = SubscriptionStatus.Suspended.transitionTo(SubscriptionStatus.Suspended)

    assert(result.isRight, "Should allow Suspended -> Suspended (no-op)")
    result.foreach { newStatus =>
      assertEquals(newStatus, SubscriptionStatus.Suspended)
    }
  }

  test("transition from Cancelled to Cancelled fails (terminal state)") {
    val result = SubscriptionStatus.Cancelled.transitionTo(SubscriptionStatus.Cancelled)

    assert(result.isLeft, "Should not allow Cancelled -> Cancelled")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidStatusTransition], "Should return InvalidStatusTransition error")
    }
  }
}
