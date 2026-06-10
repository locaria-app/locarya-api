package com.locarya.domain.models

import munit.FunSuite

class PlanSpec extends FunSuite:

  test("Freemium and Premium are distinct plans") {
    assertNotEquals(Plan.Freemium: Plan, Plan.Premium: Plan)
  }

  test("Freemium is the default plan for new providers") {
    val plan: Plan = Plan.Freemium
    assertEquals(plan, Plan.Freemium)
  }

  test("Plan variants are exhaustively matchable") {
    def label(p: Plan): String = p match
      case Plan.Freemium => "freemium"
      case Plan.Premium  => "premium"

    assertEquals(label(Plan.Freemium), "freemium")
    assertEquals(label(Plan.Premium), "premium")
  }
