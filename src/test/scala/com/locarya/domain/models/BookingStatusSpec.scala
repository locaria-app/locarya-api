package com.locarya.domain.models

import munit.FunSuite

class BookingStatusSpec extends FunSuite {

  test("transition from Pending to Confirmed succeeds") {
    val result = BookingStatus.Pending.transitionTo(BookingStatus.Confirmed)

    assert(result.isRight, "Should allow transition from Pending to Confirmed")
    result.foreach { status =>
      assertEquals(status, BookingStatus.Confirmed)
    }
  }

  test("transition from Pending to Cancelled succeeds") {
    val result = BookingStatus.Pending.transitionTo(BookingStatus.Cancelled)

    assert(result.isRight, "Should allow transition from Pending to Cancelled")
    result.foreach { status =>
      assertEquals(status, BookingStatus.Cancelled)
    }
  }

  test("transition from Confirmed to InProgress succeeds") {
    val result = BookingStatus.Confirmed.transitionTo(BookingStatus.InProgress)

    assert(result.isRight, "Should allow transition from Confirmed to InProgress")
    result.foreach { status =>
      assertEquals(status, BookingStatus.InProgress)
    }
  }

  test("transition from Confirmed to Cancelled succeeds") {
    val result = BookingStatus.Confirmed.transitionTo(BookingStatus.Cancelled)

    assert(result.isRight, "Should allow transition from Confirmed to Cancelled")
    result.foreach { status =>
      assertEquals(status, BookingStatus.Cancelled)
    }
  }

  test("transition from InProgress to Completed succeeds") {
    val result = BookingStatus.InProgress.transitionTo(BookingStatus.Completed)

    assert(result.isRight, "Should allow transition from InProgress to Completed")
    result.foreach { status =>
      assertEquals(status, BookingStatus.Completed)
    }
  }

  test("transition from InProgress to Cancelled fails") {
    val result = BookingStatus.InProgress.transitionTo(BookingStatus.Cancelled)

    assert(result.isLeft, "Cannot cancel a booking that is already in progress (equipment delivered)")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidStatusTransition], "Should return InvalidStatusTransition error")
    }
  }

  test("transition from Completed to Cancelled fails") {
    val result = BookingStatus.Completed.transitionTo(BookingStatus.Cancelled)

    assert(result.isLeft, "Completed is a terminal state")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidStatusTransition], "Should return InvalidStatusTransition error")
    }
  }

  test("transition from InProgress to Pending fails") {
    val result = BookingStatus.InProgress.transitionTo(BookingStatus.Pending)

    assert(result.isLeft, "Cannot go backwards in the lifecycle")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidStatusTransition], "Should return InvalidStatusTransition error")
    }
  }

  test("transition from InProgress to Confirmed fails") {
    val result = BookingStatus.InProgress.transitionTo(BookingStatus.Confirmed)

    assert(result.isLeft, "Cannot go backwards in the lifecycle")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidStatusTransition], "Should return InvalidStatusTransition error")
    }
  }

  test("transition from Pending to InProgress fails") {
    val result = BookingStatus.Pending.transitionTo(BookingStatus.InProgress)

    assert(result.isLeft, "Should not allow skipping Confirmed state")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidStatusTransition], "Should return InvalidStatusTransition error")
    }
  }

  test("transition from Pending to Completed fails") {
    val result = BookingStatus.Pending.transitionTo(BookingStatus.Completed)

    assert(result.isLeft, "Should not allow jumping to Completed")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidStatusTransition], "Should return InvalidStatusTransition error")
    }
  }

  test("transition from Completed to any state fails") {
    val result = BookingStatus.Completed.transitionTo(BookingStatus.Pending)

    assert(result.isLeft, "Completed is a terminal state")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidStatusTransition], "Should return InvalidStatusTransition error")
    }
  }

  test("transition from Cancelled to any state fails") {
    val result = BookingStatus.Cancelled.transitionTo(BookingStatus.Confirmed)

    assert(result.isLeft, "Cancelled is a terminal state")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidStatusTransition], "Should return InvalidStatusTransition error")
    }
  }
}
