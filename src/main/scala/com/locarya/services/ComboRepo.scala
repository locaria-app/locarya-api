package com.locarya.services

import com.locarya.core.domain.{Combo, ComboId, ComboItemDefinition}

trait ComboRepo[F[_]] extends Repository[F, Combo, ComboId] {
  def findItemsInCombo(comboId: ComboId): F[List[ComboItemDefinition]]
}
