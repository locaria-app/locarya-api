package com.locarya.services

import com.locarya.core.domain.{Provider, ProviderId, Email}

trait ProviderRepo[F[_]] extends Repository[F, Provider, ProviderId] {
  def findByEmail(email: Email): F[Option[Provider]]
}
