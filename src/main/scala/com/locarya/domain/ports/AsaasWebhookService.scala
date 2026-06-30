package com.locarya.domain.ports

trait AsaasWebhookService[F[_]]:
  def handlePaymentConfirmed(asaasChargeId: String, amount: BigDecimal): F[Unit]
