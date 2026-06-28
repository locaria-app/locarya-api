package com.locarya.helpers

import cats.effect.Ref
import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.Provider
import com.locarya.domain.ports.AsaasGateway

final class StubAsaasGateway[F[_]: Sync] private (
  returnedWalletId: String,
  counter:          Ref[F, Int]
) extends AsaasGateway[F]:

  def createSubaccount(provider: Provider): F[String] =
    counter.update(_ + 1) >> returnedWalletId.pure[F]

  def callCount: F[Int] = counter.get

object StubAsaasGateway:
  def make[F[_]: Sync](walletId: String): F[StubAsaasGateway[F]] =
    Ref.of[F, Int](0).map(new StubAsaasGateway[F](walletId, _))
