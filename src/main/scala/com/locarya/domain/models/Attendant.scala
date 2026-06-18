package com.locarya.domain.models

final case class Attendant private (
  id:         AttendantId,
  providerId: ProviderId,
  name:       String,
  phone:      String,
  isActive:   Boolean
)

object Attendant:
  def create(
    id:         AttendantId,
    providerId: ProviderId,
    name:       String,
    phone:      String,
    isActive:   Boolean = true
  ): Either[ValidationError, Attendant] =
    if name.trim.isEmpty then Left(InvalidAttendant("Name cannot be empty"))
    else Right(Attendant(id, providerId, name.trim, phone, isActive))

  extension (a: Attendant) {
    def deactivate: Attendant = a.copy(isActive = false)
  }
