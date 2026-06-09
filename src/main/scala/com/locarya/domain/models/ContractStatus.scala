package com.locarya.domain.models

sealed trait ContractStatus {
  def transitionTo(newStatus: ContractStatus): Either[ValidationError, ContractStatus] = {
    (this, newStatus) match {
      // From Active
      case (ContractStatus.Active, ContractStatus.Active) => Right(ContractStatus.Active)
      case (ContractStatus.Active, ContractStatus.Suspended) => Right(ContractStatus.Suspended)
      case (ContractStatus.Active, ContractStatus.Cancelled) => Right(ContractStatus.Cancelled)

      // From Suspended
      case (ContractStatus.Suspended, ContractStatus.Suspended) => Right(ContractStatus.Suspended)
      case (ContractStatus.Suspended, ContractStatus.Active) => Right(ContractStatus.Active)
      case (ContractStatus.Suspended, ContractStatus.Cancelled) => Right(ContractStatus.Cancelled)

      // From Cancelled (terminal state - no transitions allowed)
      case (ContractStatus.Cancelled, _) =>
        Left(InvalidStatusTransition(s"Cannot transition from Cancelled to $newStatus"))
    }
  }
}

object ContractStatus {
  case object Active extends ContractStatus
  case object Suspended extends ContractStatus
  case object Cancelled extends ContractStatus
}
