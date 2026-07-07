package com.locarya.domain.services

import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import org.typelevel.log4cats.Logger

class ItemServiceImpl[F[_]: Sync: Logger](
  itemRepo:    ItemRepository[F],
  imageRepo:   ItemImageRepository[F],
  bookingRepo: BookingRepository[F]
) extends ItemService[F]:

  def createItem(request: CreateItemRequest): F[ItemId] =
    for
      _      <- requirePositiveStock(request.stock)
      images <- liftValidation(ItemImage.create(ItemId.generate, request.imageUrls))
      item   <- liftValidation(
                  Item.create(
                    id                   = images.head.itemId,
                    providerId           = request.providerId,
                    name                 = request.name,
                    description          = request.description,
                    dailyRate            = request.dailyRate,
                    stock                = request.stock,
                    attendantRequirement = request.attendantRequirement
                  )
                )
      stored <- itemRepo.create(item)
      _      <- images.traverse_(imageRepo.create)
      _      <- Logger[F].info(
                  s"""{"event":"ItemCreated","itemId":"${stored.id.value}","providerId":"${stored.providerId.value}","name":"${stored.name}"}"""
                )
    yield stored.id

  def updateItem(request: UpdateItemRequest): F[Unit] =
    for
      existing <- requireItemExists(request.itemId)
      _        <- requireOwner(existing, request.providerId)
      _        <- requirePositiveStock(request.stock)
      newImages <- liftValidation(ItemImage.create(request.itemId, request.imageUrls))
      updated  <- liftValidation(
                    Item.create(
                      id                   = request.itemId,
                      providerId           = request.providerId,
                      name                 = request.name,
                      description          = request.description,
                      dailyRate            = request.dailyRate,
                      stock                = request.stock,
                      attendantRequirement = request.attendantRequirement
                    )
                  )
      _        <- itemRepo.update(updated)
      _        <- imageRepo.replaceImages(request.itemId, newImages)
    yield ()

  def deactivateItem(itemId: ItemId, providerId: ProviderId): F[Unit] =
    for
      existing    <- requireItemExists(itemId)
      _           <- requireOwner(existing, providerId)
      hasBookings <- bookingRepo.existsForItem(itemId)
      _           <- if hasBookings then ItemError.HasBookings(itemId).raiseError[F, Unit]
                     else ().pure[F]
      _           <- itemRepo.update(existing.deactivate)
      _           <- Logger[F].info(
                       s"""{"event":"ItemDeactivated","itemId":"${itemId.value}","providerId":"${providerId.value}"}"""
                     )
    yield ()

  def listActiveItems(providerId: ProviderId): F[List[(Item, List[ItemImage])]] =
    for
      items    <- itemRepo.findActiveByProviderId(providerId)
      itemIds   = items.map(_.id)
      imageMap <- imageRepo.findByItemIds(itemIds)
    yield items.map(item => (item, imageMap.getOrElse(item.id, Nil)))

  private def liftValidation[A](e: Either[ValidationError, A]): F[A] =
    e.fold(err => ItemError.InvalidInput(err).raiseError[F, A], _.pure[F])

  private def requirePositiveStock(stock: Int): F[Unit] =
    if stock > 0 then ().pure[F]
    else ItemError.InvalidInput(InvalidItem("Stock must be greater than zero")).raiseError

  private def requireItemExists(itemId: ItemId): F[Item] =
    itemRepo.findById(itemId).flatMap {
      case Some(item) => item.pure[F]
      case None       => ItemError.NotFound(itemId).raiseError[F, Item]
    }

  private def requireOwner(item: Item, providerId: ProviderId): F[Unit] =
    if item.providerId == providerId then ().pure[F]
    else ItemError.Forbidden(item.id).raiseError
