package com.locarya.domain.services

import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.*

class StorefrontServiceImpl[F[_]: Sync](
  providerRepo: ProviderRepository[F],
  itemRepo:     ItemRepository[F],
  imageRepo:    ItemImageRepository[F],
  comboRepo:    ComboRepository[F]
) extends StorefrontService[F]:

  def getStorefront(slug: StorefrontSlug): F[StorefrontCatalog] =
    for
      provider         <- providerRepo.findBySlug(slug).flatMap {
                            case Some(p) => p.pure[F]
                            case None    => StorefrontError.NotFound(slug).raiseError[F, Provider]
                          }
      activeItems      <- itemRepo.findActiveByProviderId(provider.id)
      itemsWithImages  <- activeItems.traverse { item =>
                            imageRepo.findByItemId(item.id).map(ItemWithImages(item, _))
                          }
      activeCombos     <- comboRepo.findActiveByProviderId(provider.id)
      combosWithComps  <- activeCombos.traverse { combo =>
                            combo.items.traverse { definition =>
                              for
                                item   <- itemRepo.findById(definition.itemId).flatMap {
                                            case Some(i) => i.pure[F]
                                            case None    =>
                                              new RuntimeException(
                                                s"Item ${definition.itemId.value} referenced in combo ${combo.id.value} not found"
                                              ).raiseError[F, Item]
                                          }
                                images <- imageRepo.findByItemId(definition.itemId)
                              yield ComboCompositionItem(item, definition.quantity, images)
                            }.map(ComboWithComposition(combo, _))
                          }
    yield StorefrontCatalog(provider, itemsWithImages, combosWithComps)
