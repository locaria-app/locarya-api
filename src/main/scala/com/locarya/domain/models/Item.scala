package com.locarya.domain.models

final case class Item private (
  id:              ItemId,
  providerId:      ProviderId,
  name:            String,
  description:     String,
  dailyRate:       Money,
  stock:           Int,
  requiresMonitor: Boolean,
  isActive:        Boolean
)

object Item {
  def create(
    id:              ItemId,
    providerId:      ProviderId,
    name:            String,
    description:     String,
    dailyRate:       Money,
    stock:           Int,
    requiresMonitor: Boolean,
    isActive:        Boolean = true
  ): Either[ValidationError, Item] = {
    if (name.trim.isEmpty) {
      Left(InvalidItem("Name cannot be empty"))
    } else if (stock < 0) {
      Left(InvalidItem("Stock cannot be negative"))
    } else {
      Right(Item(id, providerId, name.trim, description.trim, dailyRate, stock, requiresMonitor, isActive))
    }
  }

  extension (item: Item) {
    def deactivate: Item = item.copy(isActive = false)
  }
}
