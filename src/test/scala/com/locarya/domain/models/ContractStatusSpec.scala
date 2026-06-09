package com.locarya.domain.models

import munit.FunSuite

class ContractStatusSpec extends FunSuite {

  test("ContractStatus.Active exists") {
    val status: ContractStatus = ContractStatus.Active
    assert(status == ContractStatus.Active)
  }

  test("ContractStatus.Suspended exists") {
    val status: ContractStatus = ContractStatus.Suspended
    assert(status == ContractStatus.Suspended)
  }

  test("ContractStatus.Cancelled exists") {
    val status: ContractStatus = ContractStatus.Cancelled
    assert(status == ContractStatus.Cancelled)
  }

  test("transition from Active to Suspended succeeds") {
    val result = ContractStatus.Active.transitionTo(ContractStatus.Suspended)

    assert(result.isRight, "Should allow Active -> Suspended transition")
    result.foreach { newStatus =>
      assertEquals(newStatus, ContractStatus.Suspended)
    }
  }

  test("transition from Active to Cancelled succeeds") {
    val result = ContractStatus.Active.transitionTo(ContractStatus.Cancelled)

    assert(result.isRight, "Should allow Active -> Cancelled transition")
    result.foreach { newStatus =>
      assertEquals(newStatus, ContractStatus.Cancelled)
    }
  }

  test("transition from Suspended to Active succeeds") {
    val result = ContractStatus.Suspended.transitionTo(ContractStatus.Active)

    assert(result.isRight, "Should allow Suspended -> Active transition")
    result.foreach { newStatus =>
      assertEquals(newStatus, ContractStatus.Active)
    }
  }

  test("transition from Suspended to Cancelled succeeds") {
    val result = ContractStatus.Suspended.transitionTo(ContractStatus.Cancelled)

    assert(result.isRight, "Should allow Suspended -> Cancelled transition")
    result.foreach { newStatus =>
      assertEquals(newStatus, ContractStatus.Cancelled)
    }
  }

  test("transition from Cancelled to any status fails") {
    val resultToActive = ContractStatus.Cancelled.transitionTo(ContractStatus.Active)
    val resultToSuspended = ContractStatus.Cancelled.transitionTo(ContractStatus.Suspended)

    assert(resultToActive.isLeft, "Should not allow Cancelled -> Active transition")
    assert(resultToSuspended.isLeft, "Should not allow Cancelled -> Suspended transition")

    resultToActive.left.foreach { error =>
      assert(error.isInstanceOf[InvalidStatusTransition], "Should return InvalidStatusTransition error")
    }
  }

  test("transition from Active to Active is a no-op") {
    val result = ContractStatus.Active.transitionTo(ContractStatus.Active)

    assert(result.isRight, "Should allow Active -> Active (no-op)")
    result.foreach { newStatus =>
      assertEquals(newStatus, ContractStatus.Active)
    }
  }

  test("transition from Suspended to Suspended is a no-op") {
    val result = ContractStatus.Suspended.transitionTo(ContractStatus.Suspended)

    assert(result.isRight, "Should allow Suspended -> Suspended (no-op)")
    result.foreach { newStatus =>
      assertEquals(newStatus, ContractStatus.Suspended)
    }
  }

  test("transition from Cancelled to Cancelled fails (terminal state)") {
    val result = ContractStatus.Cancelled.transitionTo(ContractStatus.Cancelled)

    assert(result.isLeft, "Should not allow Cancelled -> Cancelled")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidStatusTransition], "Should return InvalidStatusTransition error")
    }
  }
}
