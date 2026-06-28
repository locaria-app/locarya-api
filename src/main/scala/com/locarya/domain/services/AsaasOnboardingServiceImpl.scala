package com.locarya.domain.services

import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.*

class AsaasOnboardingServiceImpl[F[_]: Sync](
  providerRepo: ProviderRepository[F],
  asaasGateway: AsaasGateway[F]
) extends AsaasOnboardingService[F]:

  def onboard(providerId: ProviderId): F[String] =
    providerRepo.findById(providerId).flatMap {
      case None =>
        Sync[F].raiseError(new RuntimeException(s"Provider ${providerId.value} not found"))
      case Some(provider) =>
        provider.walletId match
          case Some(existing) => existing.pure[F]
          case None =>
            for
              walletId <- asaasGateway.createSubaccount(provider)
              _        <- providerRepo.updateWalletId(providerId, walletId)
            yield walletId
    }

object AsaasOnboardingServiceImpl:
  def apply[F[_]: Sync](
    providerRepo: ProviderRepository[F],
    asaasGateway: AsaasGateway[F]
  ): AsaasOnboardingService[F] =
    new AsaasOnboardingServiceImpl[F](providerRepo, asaasGateway)
