package com.locarya.helpers

import cats.effect.Async
import cats.effect.Ref
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.BookingRepository
import java.time.LocalDate

final class InMemoryBookingRepository[F[_]: Async] private (
  state: Ref[F, Map[BookingId, Booking]]
) extends BookingRepository[F]:

  def create(booking: Booking): F[Booking] =
    state.modify { store =>
      if store.contains(booking.id) then
        (store, new RuntimeException(s"Booking ${booking.id.value} already exists").raiseError[F, Booking])
      else
        (store + (booking.id -> booking), booking.pure[F])
    }.flatten

  def findById(id: BookingId): F[Option[Booking]] =
    state.get.map(_.get(id))

  def update(booking: Booking): F[Booking] =
    state.modify(store => (store + (booking.id -> booking)) -> booking)

  def findByProvider(providerId: ProviderId): F[List[Booking]] =
    state.get.map(_.values.filter(_.providerId == providerId).toList)

  def findByStatus(status: BookingStatus): F[List[Booking]] =
    state.get.map(_.values.filter(_.status == status).toList)

  // Overlap: a booking overlaps [start, end] when bookingStart <= end && bookingEnd >= start (inclusive both ends)
  def findByDateRange(start: LocalDate, end: LocalDate): F[List[Booking]] =
    state.get.map(_.values.filter(b => !b.startDate.isAfter(end) && !b.endDate.isBefore(start)).toList)

  def existsForItem(itemId: ItemId): F[Boolean] =
    state.get.map { store =>
      store.values.exists { booking =>
        booking.items.exists {
          case BookedIndividualItem(id, _, _) => id == itemId
          case _                           => false
        }
      }
    }

  def existsForCombo(comboId: ComboId): F[Boolean] =
    state.get.map { store =>
      store.values.exists { booking =>
        booking.items.exists {
          case BookedCombo(id, _, _) => id == comboId
          case _                  => false
        }
      }
    }

object InMemoryBookingRepository:
  def make[F[_]: Async]: F[InMemoryBookingRepository[F]] =
    Ref.of[F, Map[BookingId, Booking]](Map.empty).map(new InMemoryBookingRepository(_))
