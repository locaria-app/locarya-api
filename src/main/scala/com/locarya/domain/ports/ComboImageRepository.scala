package com.locarya.domain.ports

import com.locarya.domain.models.*

trait ComboImageRepository[F[_]]:
  def create(image: ComboImage): F[ComboImage]
  def findByComboId(comboId: ComboId): F[List[ComboImage]]
  def replaceImages(comboId: ComboId, images: List[ComboImage]): F[Unit]
