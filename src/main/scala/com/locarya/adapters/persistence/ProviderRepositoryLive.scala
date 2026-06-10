package com.locarya.adapters.persistence

import cats.effect.Async
import com.locarya.domain.models.*
import com.locarya.domain.ports.ProviderRepository
import doobie.Transactor

// Doobie-backed ProviderRepository.
// SQL implementations are deferred to the persistence adapter issue.
class ProviderRepositoryLive[F[_]: Async] private (xa: Transactor[F])
    extends ProviderRepository[F]:

  def create(provider: Provider): F[Provider] =
    Async[F].raiseError(new NotImplementedError("ProviderRepositoryLive.create not yet implemented"))

  def findById(id: ProviderId): F[Option[Provider]] =
    Async[F].raiseError(new NotImplementedError("ProviderRepositoryLive.findById not yet implemented"))

  def update(provider: Provider): F[Provider] =
    Async[F].raiseError(new NotImplementedError("ProviderRepositoryLive.update not yet implemented"))

  def findByEmail(email: Email): F[Option[Provider]] =
    Async[F].raiseError(new NotImplementedError("ProviderRepositoryLive.findByEmail not yet implemented"))

  def findBySlug(slug: StorefrontSlug): F[Option[Provider]] =
    Async[F].raiseError(new NotImplementedError("ProviderRepositoryLive.findBySlug not yet implemented"))

object ProviderRepositoryLive:
  def make[F[_]: Async](xa: Transactor[F]): ProviderRepository[F] =
    new ProviderRepositoryLive[F](xa)
