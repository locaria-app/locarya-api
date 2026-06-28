package com.locarya.adapters.external

import cats.effect.Sync
import com.locarya.domain.models.Provider
import com.locarya.domain.ports.AsaasGateway

class AsaasGatewayStub[F[_]: Sync] extends AsaasGateway[F]:
  def createSubaccount(provider: Provider): F[String] =
    Sync[F].raiseError(new NotImplementedError("Real Asaas HTTP adapter is not yet implemented"))

object AsaasGatewayStub:
  def make[F[_]: Sync]: AsaasGateway[F] = new AsaasGatewayStub[F]
