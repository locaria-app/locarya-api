package com.locarya.domain.models

sealed trait AttendantRequirement

object AttendantRequirement {
  case object Required extends AttendantRequirement
  case object Optional extends AttendantRequirement
  case object NotAllowed extends AttendantRequirement
}
