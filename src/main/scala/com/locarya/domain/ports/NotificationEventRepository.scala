package com.locarya.domain.ports

import com.locarya.domain.models.*

trait NotificationEventRepository[F[_]] extends Repository[F, NotificationEvent, NotificationEventId]
