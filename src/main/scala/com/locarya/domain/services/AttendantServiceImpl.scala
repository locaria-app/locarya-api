package com.locarya.domain.services

import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import org.typelevel.log4cats.Logger

class AttendantServiceImpl[F[_]: Sync: Logger](
  attendantRepo: AttendantRepository[F],
  bookingRepo:   BookingRepository[F]
) extends AttendantService[F]:

  def createAttendant(request: CreateAttendantRequest): F[AttendantId] =
    for
      attendant <- liftValidation(Attendant.create(AttendantId.generate, request.providerId, request.name, request.phone))
      stored    <- attendantRepo.create(attendant)
      _         <- Logger[F].info(
                     s"""{"event":"AttendantCreated","attendantId":"${stored.id.value}","providerId":"${stored.providerId.value}"}"""
                   )
    yield stored.id

  def updateAttendant(request: UpdateAttendantRequest): F[Unit] =
    for
      existing <- requireAttendantExists(request.attendantId)
      _        <- requireOwner(existing, request.providerId)
      updated  <- liftValidation(
                    Attendant.create(request.attendantId, request.providerId, request.name, request.phone, isActive = existing.isActive)
                  )
      _        <- attendantRepo.update(updated)
    yield ()

  def deactivateAttendant(attendantId: AttendantId, providerId: ProviderId): F[Unit] =
    for
      existing <- requireAttendantExists(attendantId)
      _        <- requireOwner(existing, providerId)
      _        <- attendantRepo.update(existing.deactivate)
    yield ()

  def listActiveAttendants(providerId: ProviderId): F[List[Attendant]] =
    attendantRepo.findActiveByProvider(providerId)

  def assignAttendants(request: AssignAttendantsRequest): F[Unit] =
    request.attendantIds.traverse_ { attendantId =>
      for
        attendant <- requireAttendantExists(attendantId)
        _         <- requireOwner(attendant, request.providerId)
        _         <- if !attendant.isActive then AttendantError.AttendantInactive(attendantId).raiseError[F, Unit]
                     else ().pure[F]
        _         <- attendantRepo.assignToBooking(request.bookingId, attendantId)
      yield ()
    }

  private def requireAttendantExists(id: AttendantId): F[Attendant] =
    attendantRepo.findById(id).flatMap {
      case Some(a) => a.pure[F]
      case None    => AttendantError.AttendantNotFound(id).raiseError[F, Attendant]
    }

  private def requireOwner(attendant: Attendant, providerId: ProviderId): F[Unit] =
    if attendant.providerId == providerId then ().pure[F]
    else AttendantError.Forbidden(attendant.id).raiseError

  private def liftValidation[A](e: Either[ValidationError, A]): F[A] =
    e.fold(err => AttendantError.InvalidInput(err).raiseError[F, A], _.pure[F])
