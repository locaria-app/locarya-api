package com.locarya.helpers

import cats.effect.Async
import cats.effect.Ref
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.ComboRepository

final class InMemoryComboRepository[F[_]: Async] private (
  state: Ref[F, Map[ComboId, Combo]]
) extends ComboRepository[F]:

  def create(combo: Combo): F[Combo] =
    state.modify { store =>
      if store.contains(combo.id) then
        (store, new RuntimeException(s"Combo ${combo.id.value} already exists").raiseError[F, Combo])
      else
        (store + (combo.id -> combo), combo.pure[F])
    }.flatten

  def findById(id: ComboId): F[Option[Combo]] =
    state.get.map(_.get(id))

  def update(combo: Combo): F[Combo] =
    state.modify(store => (store + (combo.id -> combo)) -> combo)

  def findItemsInCombo(comboId: ComboId): F[List[ComboItemDefinition]] =
    state.get.map(_.get(comboId).map(_.items).getOrElse(Nil))

  def findByProviderId(providerId: ProviderId): F[List[Combo]] =
    state.get.map(_.values.filter(_.providerId == providerId).toList)

  def findActiveByProviderId(providerId: ProviderId): F[List[Combo]] =
    state.get.map(_.values.filter(c => c.providerId == providerId && c.isActive).toList)

object InMemoryComboRepository:
  def make[F[_]: Async]: F[InMemoryComboRepository[F]] =
    Ref.of[F, Map[ComboId, Combo]](Map.empty).map(new InMemoryComboRepository(_))
