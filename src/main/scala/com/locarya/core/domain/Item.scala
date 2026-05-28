package com.locarya.core.domain

final case class Item private (
  id: ItemId,
  providerId: ProviderId,
  name: String,
  description: String,
  dailyRate: Money,
  stock: Int,
  attendantRequirement: AttendantRequirement
)

object Item {
  def create(
    id: ItemId,
    providerId: ProviderId,
    name: String,
    description: String,
    dailyRate: Money,
    stock: Int,
    attendantRequirement: AttendantRequirement
  ): Either[ValidationError, Item] = {
    if (name.trim.isEmpty) {
      Left(InvalidItem("Name cannot be empty"))
    } else if (stock < 0) {
      Left(InvalidItem("Stock cannot be negative"))
    } else {
      Right(Item(id, providerId, name.trim, description.trim, dailyRate, stock, attendantRequirement))
    }
  }
}
