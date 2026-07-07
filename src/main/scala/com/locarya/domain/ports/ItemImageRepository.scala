package com.locarya.domain.ports

import com.locarya.domain.models.*

trait ItemImageRepository[F[_]]:
  def create(image: ItemImage): F[ItemImage]
  def findByItemId(itemId: ItemId): F[List[ItemImage]]
  def findByItemIds(itemIds: List[ItemId]): F[Map[ItemId, List[ItemImage]]]
  def replaceImages(itemId: ItemId, images: List[ItemImage]): F[Unit]
