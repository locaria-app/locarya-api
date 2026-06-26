package com.locarya.domain.models

import java.time.LocalDate

final case class Provider private (
  id:             ProviderId,
  email:          Email,
  taxId:          TaxId,
  businessName:   String,
  tradeName:      String,
  city:           String,
  state:          String,
  passwordHash:   String,
  planTier:       PlanTier,
  storefrontSlug: StorefrontSlug,
  isActive:       Boolean,
  storeConfig:    StoreConfig    = StoreConfig()
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
    passwordHash:   String         = "",
    planTier:       PlanTier       = PlanTier.Freemium,
    storefrontSlug: StorefrontSlug = StorefrontSlug.fromString("placeholder-000000").toOption.get,
    isActive:       Boolean        = true,
    storeConfig:    StoreConfig    = StoreConfig()
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
          passwordHash, planTier, storefrontSlug,
          isActive, storeConfig
        )
      )

  def hasActiveSubscriptionOn(subscription: Subscription, date: LocalDate): Boolean =
    subscription.isActiveOn(date)

  extension (p: Provider) {
    def deactivate: Provider                          = p.copy(isActive = false)
    def withStoreConfig(config: StoreConfig): Provider = p.copy(storeConfig = config)
  }
