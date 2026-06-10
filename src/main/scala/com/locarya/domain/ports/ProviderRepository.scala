package com.locarya.domain.ports

import com.locarya.domain.models.*

trait ProviderRepository[F[_]] extends Repository[F, Provider, ProviderId]:
  def findByEmail(email: Email): F[Option[Provider]]
