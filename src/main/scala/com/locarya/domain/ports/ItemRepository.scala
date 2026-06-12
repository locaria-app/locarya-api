package com.locarya.domain.ports

import com.locarya.domain.models.*

trait ItemRepository[F[_]] extends Repository[F, Item, ItemId]:
  def findByProviderId(providerId: ProviderId): F[List[Item]]
  def findActiveByProviderId(providerId: ProviderId): F[List[Item]]
