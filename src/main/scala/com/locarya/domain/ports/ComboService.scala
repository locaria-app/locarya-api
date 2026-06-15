package com.locarya.domain.ports

import com.locarya.domain.models.*

final case class CreateComboRequest(
  providerId:       ProviderId,
  name:             String,
  description:      String,
  dailyRate:        Money,
  itemCompositions: List[ComboItemDefinition]
)

final case class UpdateComboRequest(
  comboId:          ComboId,
  providerId:       ProviderId,
  name:             String,
  description:      String,
  dailyRate:        Money,
  itemCompositions: Option[List[ComboItemDefinition]]
)

trait ComboService[F[_]]:
  def createCombo(request: CreateComboRequest): F[ComboId]
  def getCombo(comboId: ComboId, providerId: ProviderId): F[Combo]
  def updateCombo(request: UpdateComboRequest): F[Unit]
  def softDeleteCombo(comboId: ComboId, providerId: ProviderId): F[Unit]
