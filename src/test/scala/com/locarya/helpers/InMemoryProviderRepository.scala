package com.locarya.helpers

import cats.effect.Async
import cats.effect.Ref
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.ProviderRepository

final class InMemoryProviderRepository[F[_]: Async] private (
  state: Ref[F, Map[ProviderId, Provider]]
) extends ProviderRepository[F]:

  def create(provider: Provider): F[Provider] =
    state.modify { store =>
      if store.contains(provider.id) then
        (store, new RuntimeException(s"Provider ${provider.id.value} already exists").raiseError[F, Provider])
      else
        (store + (provider.id -> provider), provider.pure[F])
    }.flatten

  def findById(id: ProviderId): F[Option[Provider]] =
    state.get.map(_.get(id))

  def update(provider: Provider): F[Provider] =
    state.modify(store => (store + (provider.id -> provider)) -> provider)

  def findByEmail(email: Email): F[Option[Provider]] =
    state.get.map(_.values.find(_.email == email))

  def findBySlug(slug: StorefrontSlug): F[Option[Provider]] =
    state.get.map(_.values.find(_.storefrontSlug == slug))

  def updateStoreConfig(id: ProviderId, config: StoreConfig): F[Provider] =
    state.modify { store =>
      store.get(id) match
        case None    => (store, new RuntimeException(s"Provider ${id.value} not found").raiseError[F, Provider])
        case Some(p) =>
          val updated = p.withStoreConfig(config)
          (store + (id -> updated), updated.pure[F])
    }.flatten

  def updateWalletId(id: ProviderId, walletId: String): F[Provider] =
    state.modify { store =>
      store.get(id) match
        case None    => (store, new RuntimeException(s"Provider ${id.value} not found").raiseError[F, Provider])
        case Some(p) =>
          val updated = p.withWalletId(walletId)
          (store + (id -> updated), updated.pure[F])
    }.flatten

object InMemoryProviderRepository:
  def make[F[_]: Async]: F[InMemoryProviderRepository[F]] =
    Ref.of[F, Map[ProviderId, Provider]](Map.empty).map(new InMemoryProviderRepository(_))
