package com.locarya.domain.ports

import com.locarya.domain.models.ProviderId

trait AsaasOnboardingService[F[_]]:
  def onboard(providerId: ProviderId): F[String]
