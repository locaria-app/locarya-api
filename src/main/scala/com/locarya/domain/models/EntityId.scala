package com.locarya.domain.models

import java.util.UUID

// ProviderId
final case class ProviderId private (value: String)

object ProviderId {
  def generate: ProviderId = ProviderId(UUID.randomUUID().toString)

  def fromString(id: String): Either[ValidationError, ProviderId] = {
    try {
      UUID.fromString(id) // validates UUID format
      Right(ProviderId(id))
    } catch {
      case _: IllegalArgumentException =>
        Left(InvalidEntityId(s"Invalid ProviderId format: $id"))
    }
  }
}

// CustomerId
final case class CustomerId private (value: String)

object CustomerId {
  def generate: CustomerId = CustomerId(UUID.randomUUID().toString)

  def fromString(id: String): Either[ValidationError, CustomerId] = {
    try {
      UUID.fromString(id)
      Right(CustomerId(id))
    } catch {
      case _: IllegalArgumentException =>
        Left(InvalidEntityId(s"Invalid CustomerId format: $id"))
    }
  }
}

// ItemId
final case class ItemId private (value: String)

object ItemId {
  def generate: ItemId = ItemId(UUID.randomUUID().toString)

  def fromString(id: String): Either[ValidationError, ItemId] = {
    try {
      UUID.fromString(id)
      Right(ItemId(id))
    } catch {
      case _: IllegalArgumentException =>
        Left(InvalidEntityId(s"Invalid ItemId format: $id"))
    }
  }
}

// ComboId
final case class ComboId private (value: String)

object ComboId {
  def generate: ComboId = ComboId(UUID.randomUUID().toString)

  def fromString(id: String): Either[ValidationError, ComboId] = {
    try {
      UUID.fromString(id)
      Right(ComboId(id))
    } catch {
      case _: IllegalArgumentException =>
        Left(InvalidEntityId(s"Invalid ComboId format: $id"))
    }
  }
}

// BookingId
final case class BookingId private (value: String)

object BookingId {
  def generate: BookingId = BookingId(UUID.randomUUID().toString)

  def fromString(id: String): Either[ValidationError, BookingId] = {
    try {
      UUID.fromString(id)
      Right(BookingId(id))
    } catch {
      case _: IllegalArgumentException =>
        Left(InvalidEntityId(s"Invalid BookingId format: $id"))
    }
  }
}

// AttendantId
final case class AttendantId private (value: String)

object AttendantId {
  def generate: AttendantId = AttendantId(UUID.randomUUID().toString)

  def fromString(id: String): Either[ValidationError, AttendantId] = {
    try {
      UUID.fromString(id)
      Right(AttendantId(id))
    } catch {
      case _: IllegalArgumentException =>
        Left(InvalidEntityId(s"Invalid AttendantId format: $id"))
    }
  }
}

// PaymentId
final case class PaymentId private (value: String)

object PaymentId {
  def generate: PaymentId = PaymentId(UUID.randomUUID().toString)

  def fromString(id: String): Either[ValidationError, PaymentId] = {
    try {
      UUID.fromString(id)
      Right(PaymentId(id))
    } catch {
      case _: IllegalArgumentException =>
        Left(InvalidEntityId(s"Invalid PaymentId format: $id"))
    }
  }
}

// ItemImageId
final case class ItemImageId private (value: String)

object ItemImageId {
  def generate: ItemImageId = ItemImageId(UUID.randomUUID().toString)

  def fromString(id: String): Either[ValidationError, ItemImageId] = {
    try {
      UUID.fromString(id)
      Right(ItemImageId(id))
    } catch {
      case _: IllegalArgumentException =>
        Left(InvalidEntityId(s"Invalid ItemImageId format: $id"))
    }
  }
}

// PlanId
final case class PlanId private (value: String)

object PlanId {
  def generate: PlanId = PlanId(UUID.randomUUID().toString)

  def fromString(id: String): Either[ValidationError, PlanId] = {
    try {
      UUID.fromString(id)
      Right(PlanId(id))
    } catch {
      case _: IllegalArgumentException =>
        Left(InvalidEntityId(s"Invalid PlanId format: $id"))
    }
  }
}

// SubscriptionId
final case class SubscriptionId private (value: String)

object SubscriptionId {
  def generate: SubscriptionId = SubscriptionId(UUID.randomUUID().toString)

  def fromString(id: String): Either[ValidationError, SubscriptionId] = {
    try {
      UUID.fromString(id)
      Right(SubscriptionId(id))
    } catch {
      case _: IllegalArgumentException =>
        Left(InvalidEntityId(s"Invalid SubscriptionId format: $id"))
    }
  }
}

// BookingChargeId
final case class BookingChargeId private (value: String)

object BookingChargeId {
  def generate: BookingChargeId = BookingChargeId(UUID.randomUUID().toString)

  def fromString(id: String): Either[ValidationError, BookingChargeId] = {
    try {
      UUID.fromString(id)
      Right(BookingChargeId(id))
    } catch {
      case _: IllegalArgumentException =>
        Left(InvalidEntityId(s"Invalid BookingChargeId format: $id"))
    }
  }
}

// NotificationEventId
final case class NotificationEventId private (value: String)

object NotificationEventId {
  def generate: NotificationEventId = NotificationEventId(UUID.randomUUID().toString)

  def fromString(id: String): Either[ValidationError, NotificationEventId] = {
    try {
      UUID.fromString(id)
      Right(NotificationEventId(id))
    } catch {
      case _: IllegalArgumentException =>
        Left(InvalidEntityId(s"Invalid NotificationEventId format: $id"))
    }
  }
}
