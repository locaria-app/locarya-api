package com.locarya.domain.models

final case class Provider private (
  id: ProviderId,
  email: Email,
  taxId: TaxId,
  businessName: String,
  tradeName: String,
  city: String,
  state: String,
  contractStatus: ContractStatus
)

object Provider {
  def create(
    id: ProviderId,
    email: Email,
    taxId: TaxId,
    businessName: String,
    tradeName: String,
    city: String,
    state: String,
    contractStatus: ContractStatus = ContractStatus.Active
  ): Either[ValidationError, Provider] = {
    if (businessName.trim.isEmpty) {
      Left(InvalidProvider("Business name cannot be empty"))
    } else if (tradeName.trim.isEmpty) {
      Left(InvalidProvider("Trade name cannot be empty"))
    } else if (city.trim.isEmpty) {
      Left(InvalidProvider("City cannot be empty"))
    } else if (state.trim.isEmpty) {
      Left(InvalidProvider("State cannot be empty"))
    } else {
      Right(Provider(id, email, taxId, businessName.trim, tradeName.trim, city.trim, state.trim, contractStatus))
    }
  }
}
