package com.locarya.domain.models

import munit.FunSuite

class AttendantRequirementSpec extends FunSuite {

  test("AttendantRequirement.Required exists") {
    val requirement: AttendantRequirement = AttendantRequirement.Required
    assert(requirement == AttendantRequirement.Required)
  }

  test("AttendantRequirement.Optional exists") {
    val requirement: AttendantRequirement = AttendantRequirement.Optional
    assert(requirement == AttendantRequirement.Optional)
  }

  test("AttendantRequirement.NotAllowed exists") {
    val requirement: AttendantRequirement = AttendantRequirement.NotAllowed
    assert(requirement == AttendantRequirement.NotAllowed)
  }

  test("AttendantRequirement values are distinct") {
    assert(AttendantRequirement.Required != AttendantRequirement.Optional)
    assert(AttendantRequirement.Required != AttendantRequirement.NotAllowed)
    assert(AttendantRequirement.Optional != AttendantRequirement.NotAllowed)
  }
}
