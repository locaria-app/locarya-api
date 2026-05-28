package com.locarya.core.domain

final case class Provider private (
  id: ProviderId,
  email: Email,
  cnpj: CNPJ,
  businessName: String,
  tradeName: String,
  contractStatus: ContractStatus
)

object Provider {
  def create(
    id: ProviderId,
    email: Email,
    cnpj: CNPJ,
    businessName: String,
    tradeName: String,
    contractStatus: ContractStatus = ContractStatus.Active
  ): Either[ValidationError, Provider] = {
    if (businessName.trim.isEmpty) {
      Left(InvalidProvider("Business name cannot be empty"))
    } else if (tradeName.trim.isEmpty) {
      Left(InvalidProvider("Trade name cannot be empty"))
    } else {
      Right(Provider(id, email, cnpj, businessName.trim, tradeName.trim, contractStatus))
    }
  }
}
