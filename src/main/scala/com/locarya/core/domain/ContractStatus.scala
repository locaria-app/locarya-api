package com.locarya.core.domain

sealed trait ContractStatus

object ContractStatus {
  case object Active extends ContractStatus
  case object Suspended extends ContractStatus
  case object Cancelled extends ContractStatus
}
