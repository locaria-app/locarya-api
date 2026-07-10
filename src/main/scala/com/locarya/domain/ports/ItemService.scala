package com.locarya.domain.ports

import com.locarya.domain.models.*

final case class CreateItemRequest(
  providerId:      ProviderId,
  name:            String,
  description:     String,
  dailyRate:       Money,
  stock:           Int,
  requiresMonitor: Boolean,
  imageUrls:       List[String]
)

final case class UpdateItemRequest(
  itemId:          ItemId,
  providerId:      ProviderId,
  name:            String,
  description:     String,
  dailyRate:       Money,
  stock:           Int,
  requiresMonitor: Boolean,
  imageUrls:       List[String]
)

trait ItemService[F[_]]:
  def createItem(request: CreateItemRequest): F[ItemId]
  def updateItem(request: UpdateItemRequest): F[Unit]
  def deactivateItem(itemId: ItemId, providerId: ProviderId): F[Unit]
  def activateItem(itemId: ItemId, providerId: ProviderId): F[Unit]
  def listItems(providerId: ProviderId): F[List[(Item, List[ItemImage])]]
