package com.locarya.domain.ports

import com.locarya.domain.models.*

final case class CreateAttendantRequest(
  providerId: ProviderId,
  name:       String,
  phone:      String
)

final case class UpdateAttendantRequest(
  attendantId: AttendantId,
  providerId:  ProviderId,
  name:        String,
  phone:       String
)

final case class AssignAttendantsRequest(
  bookingId:    BookingId,
  providerId:   ProviderId,
  lineRef:      BookingLineRef,
  attendantIds: List[AttendantId]
)

final case class RemoveAttendantFromLineRequest(
  bookingId:   BookingId,
  providerId:  ProviderId,
  lineRef:     BookingLineRef,
  attendantId: AttendantId
)

trait AttendantService[F[_]]:
  def createAttendant(request: CreateAttendantRequest): F[AttendantId]
  def updateAttendant(request: UpdateAttendantRequest): F[Unit]
  def deactivateAttendant(attendantId: AttendantId, providerId: ProviderId): F[Unit]
  def listActiveAttendants(providerId: ProviderId): F[List[Attendant]]
  def assignAttendants(request: AssignAttendantsRequest): F[Unit]
  def removeAttendantFromLine(request: RemoveAttendantFromLineRequest): F[Unit]
