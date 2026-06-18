package com.locarya.helpers

import cats.effect.Async
import cats.effect.Ref
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.AttendantRepository

final class InMemoryAttendantRepository[F[_]: Async] private (
  state:     Ref[F, Map[AttendantId, Attendant]],
  joinState: Ref[F, Map[BookingId, Set[AttendantId]]]
) extends AttendantRepository[F]:

  def create(attendant: Attendant): F[Attendant] =
    state.modify { store =>
      if store.contains(attendant.id) then
        (store, new RuntimeException(s"Attendant ${attendant.id.value} already exists").raiseError[F, Attendant])
      else
        (store + (attendant.id -> attendant), attendant.pure[F])
    }.flatten

  def findById(id: AttendantId): F[Option[Attendant]] =
    state.get.map(_.get(id))

  def update(attendant: Attendant): F[Attendant] =
    state.modify(store => (store + (attendant.id -> attendant)) -> attendant)

  def findByProvider(providerId: ProviderId): F[List[Attendant]] =
    state.get.map(_.values.filter(_.providerId == providerId).toList)

  def findActiveByProvider(providerId: ProviderId): F[List[Attendant]] =
    state.get.map(_.values.filter(a => a.providerId == providerId && a.isActive).toList)

  def findByBooking(bookingId: BookingId): F[List[Attendant]] =
    for
      join         <- joinState.get
      attendantIds  = join.getOrElse(bookingId, Set.empty)
      allAttendants <- state.get
    yield attendantIds.flatMap(allAttendants.get).toList

  def assignToBooking(bookingId: BookingId, attendantId: AttendantId): F[Unit] =
    joinState.update(store =>
      store + (bookingId -> (store.getOrElse(bookingId, Set.empty) + attendantId))
    )

  def removeFromBooking(bookingId: BookingId, attendantId: AttendantId): F[Unit] =
    joinState.update(store =>
      store + (bookingId -> (store.getOrElse(bookingId, Set.empty) - attendantId))
    )

object InMemoryAttendantRepository:
  def make[F[_]: Async]: F[InMemoryAttendantRepository[F]] =
    for
      state     <- Ref.of[F, Map[AttendantId, Attendant]](Map.empty)
      joinState <- Ref.of[F, Map[BookingId, Set[AttendantId]]](Map.empty)
    yield new InMemoryAttendantRepository(state, joinState)
