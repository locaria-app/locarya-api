package com.locarya.helpers

import cats.effect.Ref
import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.NotificationPayload
import com.locarya.domain.ports.NotificationService

final class NotificationServiceStub[F[_]: Sync] private (
  ref: Ref[F, List[NotificationPayload]]
) extends NotificationService[F]:

  def notify(payload: NotificationPayload): F[Unit] =
    ref.update(payload :: _)

  def captured: F[List[NotificationPayload]] =
    ref.get.map(_.reverse)

object NotificationServiceStub:
  def make[F[_]: Sync]: F[NotificationServiceStub[F]] =
    Ref.of[F, List[NotificationPayload]](Nil).map(new NotificationServiceStub(_))
