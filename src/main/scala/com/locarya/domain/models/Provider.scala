package com.locarya.domain.models

final case class Provider private (
  id:              ProviderId,
  email:           Email,
  taxId:           TaxId,
  businessName:    String,
  tradeName:       String,
  city:            String,
  state:           String,
  contractStatus:  ContractStatus,
  passwordHash:    String,
  planTier:        PlanTier,
  storefrontSlug:  StorefrontSlug,
  isActive:        Boolean
)

object Provider:
  def create(
    id:             ProviderId,
    email:          Email,
    taxId:          TaxId,
    businessName:   String,
    tradeName:      String,
    city:           String,
    state:          String,
    contractStatus: ContractStatus  = ContractStatus.Active,
    passwordHash:   String          = "",
    planTier:       PlanTier        = PlanTier.Freemium,
    storefrontSlug: StorefrontSlug  = StorefrontSlug.fromString("placeholder-000000").toOption.get,
    isActive:       Boolean         = true
  ): Either[ValidationError, Provider] =
    if businessName.trim.isEmpty then
      Left(InvalidProvider("Business name cannot be empty"))
    else if tradeName.trim.isEmpty then
      Left(InvalidProvider("Trade name cannot be empty"))
    else if city.trim.isEmpty then
      Left(InvalidProvider("City cannot be empty"))
    else if state.trim.isEmpty then
      Left(InvalidProvider("State cannot be empty"))
    else
      Right(
        Provider(
          id, email, taxId,
          businessName.trim, tradeName.trim,
          city.trim, state.trim,
          contractStatus,
          passwordHash, planTier, storefrontSlug,
          isActive
        )
      )

  extension (p: Provider) {
    def deactivate: Provider = p.copy(isActive = false)
  }
