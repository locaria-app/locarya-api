package com.locarya.domain.ports

import com.locarya.domain.models.*

final case class CreateComboRequest(
  providerId:       ProviderId,
  name:             String,
  description:      String,
  dailyRate:        Money,
  itemCompositions: List[ComboItemDefinition],
  imageUrls:        List[String]
)

final case class UpdateComboRequest(
  comboId:          ComboId,
  providerId:       ProviderId,
  name:             String,
  description:      String,
  dailyRate:        Money,
  itemCompositions: Option[List[ComboItemDefinition]],
  imageUrls:        List[String]
)

trait ComboService[F[_]]:
  def createCombo(request: CreateComboRequest): F[ComboId]
  def getCombo(comboId: ComboId, providerId: ProviderId): F[(Combo, List[ComboImage])]
  def updateCombo(request: UpdateComboRequest): F[Unit]
  def softDeleteCombo(comboId: ComboId, providerId: ProviderId): F[Unit]
  def activateCombo(comboId: ComboId, providerId: ProviderId): F[Unit]
  def listCombos(providerId: ProviderId): F[List[(Combo, List[ComboImage])]]
