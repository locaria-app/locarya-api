package com.locarya.domain.ports

import com.locarya.domain.models.NotificationPayload

trait NotificationService[F[_]]:
  def notify(payload: NotificationPayload): F[Unit]
