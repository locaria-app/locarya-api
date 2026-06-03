package com.locarya.services

import com.locarya.core.domain.{Item, ItemId, ProviderId}

trait ItemRepo[F[_]] extends Repository[F, Item, ItemId] {
  def findByProviderId(providerId: ProviderId): F[List[Item]]
}
