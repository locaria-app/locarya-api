package com.locarya.domain.models

final case class AsaasCharge private (chargeId: String, paymentUrl: String)

object AsaasCharge:
  def create(chargeId: String, paymentUrl: String): Either[InvalidAsaasCharge, AsaasCharge] =
    if chargeId.isEmpty then Left(InvalidAsaasCharge("chargeId must not be empty"))
    else if paymentUrl.isEmpty then Left(InvalidAsaasCharge("paymentUrl must not be empty"))
    else Right(AsaasCharge(chargeId, paymentUrl))
