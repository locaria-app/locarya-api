package com.locarya.core.domain

final case class ComboItemDefinition(itemId: ItemId, quantity: Int)

final case class Combo private (
  id: ComboId,
  providerId: ProviderId,
  name: String,
  description: String,
  dailyRate: Money,
  items: List[ComboItemDefinition],
  attendantRequirement: AttendantRequirement
)

object Combo {
  def create(
    id: ComboId,
    providerId: ProviderId,
    name: String,
    description: String,
    dailyRate: Money,
    items: List[ComboItemDefinition],
    attendantRequirement: AttendantRequirement
  ): Either[ValidationError, Combo] = {
    if (name.trim.isEmpty) {
      Left(InvalidCombo("Name cannot be empty"))
    } else if (items.isEmpty) {
      Left(InvalidCombo("Combo must contain at least one item"))
    } else if (items.exists(_.quantity <= 0)) {
      Left(InvalidCombo("All item quantities must be positive"))
    } else {
      Right(Combo(id, providerId, name.trim, description.trim, dailyRate, items, attendantRequirement))
    }
  }
}
