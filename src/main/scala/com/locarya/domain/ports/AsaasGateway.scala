package com.locarya.domain.ports

import com.locarya.domain.models.Provider

trait AsaasGateway[F[_]]:
  def createSubaccount(provider: Provider): F[String]
