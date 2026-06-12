package com.locarya.helpers

import cats.effect.Async
import cats.effect.Ref
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.ItemRepository

final class InMemoryItemRepository[F[_]: Async] private (
  state: Ref[F, Map[ItemId, Item]]
) extends ItemRepository[F]:

  def create(item: Item): F[Item] =
    state.modify { store =>
      if store.contains(item.id) then
        (store, new RuntimeException(s"Item ${item.id.value} already exists").raiseError[F, Item])
      else
        (store + (item.id -> item), item.pure[F])
    }.flatten

  def findById(id: ItemId): F[Option[Item]] =
    state.get.map(_.get(id))

  def update(item: Item): F[Item] =
    state.modify(store => (store + (item.id -> item)) -> item)

  def findByProviderId(providerId: ProviderId): F[List[Item]] =
    state.get.map(_.values.filter(_.providerId == providerId).toList)

  def findActiveByProviderId(providerId: ProviderId): F[List[Item]] =
    state.get.map(_.values.filter(i => i.providerId == providerId && i.isActive).toList)

object InMemoryItemRepository:
  def make[F[_]: Async]: F[InMemoryItemRepository[F]] =
    Ref.of[F, Map[ItemId, Item]](Map.empty).map(new InMemoryItemRepository(_))
