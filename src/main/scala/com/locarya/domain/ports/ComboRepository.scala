package com.locarya.domain.ports

import com.locarya.domain.models.*

trait ComboRepository[F[_]] extends Repository[F, Combo, ComboId]:
  def findItemsInCombo(comboId: ComboId): F[List[ComboItemDefinition]]
  def findActiveByProviderId(providerId: ProviderId): F[List[Combo]]
