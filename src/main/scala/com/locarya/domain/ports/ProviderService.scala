package com.locarya.domain.ports

import com.locarya.domain.models.*

final case class SignupRequest(
  email:    String,
  password: String,
  name:     String,
  city:     String,
  state:    String,
  cpf:      Option[String],
  cnpj:     Option[String]
)

final case class SignupResult(
  providerId:     ProviderId,
  storefrontSlug: StorefrontSlug
)

trait ProviderService[F[_]]:
  def signup(request: SignupRequest): F[SignupResult]
  def findById(id: ProviderId): F[Option[Provider]]
  def updateStoreConfig(providerId: ProviderId, config: StoreConfig): F[Provider]
