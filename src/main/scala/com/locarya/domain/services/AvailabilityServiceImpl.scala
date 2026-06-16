package com.locarya.domain.services

import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import java.time.LocalDate
import org.typelevel.log4cats.Logger

class AvailabilityServiceImpl[F[_]: Sync: Logger](
  itemRepo:    ItemRepository[F],
  comboRepo:   ComboRepository[F],
  bookingRepo: BookingRepository[F]
) extends AvailabilityService[F]:

  def checkAvailability(
    items:            List[(ItemId, Int)],
    date:             LocalDate,
    excludeBookingId: Option[BookingId]
  ): F[AvailabilityResult] =
    for
      bookings           <- bookingsBlockingDate(date, excludeBookingId)
      consumption        <- stockConsumption(bookings)
      unavailableEntries <- items.flatTraverse { case (id, qty) =>
                              checkEntry(id, qty, consumption)
                            }
      result              = AvailabilityResult(
                              available        = unavailableEntries.isEmpty,
                              unavailableItems = unavailableEntries
                            )
      _                  <- logChecked(items, date, result)
    yield result

  private def logChecked(
    items:  List[(ItemId, Int)],
    date:   LocalDate,
    result: AvailabilityResult
  ): F[Unit] =
    val itemIds = items.map(_._1.value).map(v => s""""$v"""").mkString(",")
    val reasons = result.unavailableItems
      .map(u => s""""${u.reason.message}"""")
      .distinct
      .mkString(",")
    Logger[F].info(
      s"""{"event":"AvailabilityChecked","itemIds":[$itemIds],"date":"${date.toString}","available":${result.available},"reasons":[$reasons]}"""
    )

  // Bookings that *block* a date: status is Confirmed or InProgress, the booking
  // covers `date`, and (if provided) it is not the booking being edited.
  private def bookingsBlockingDate(
    date:             LocalDate,
    excludeBookingId: Option[BookingId]
  ): F[List[Booking]] =
    bookingRepo.findByDateRange(date, date).map { all =>
      all.filter { b =>
        val statusBlocks = b.status == BookingStatus.Confirmed || b.status == BookingStatus.InProgress
        val notExcluded  = !excludeBookingId.contains(b.id)
        statusBlocks && notExcluded
      }
    }

  // Per-Item consumed quantity. BookedIndividualItem entries count directly;
  // BookedCombo entries decompose into their constituents (quantity * comboQty).
  private def stockConsumption(bookings: List[Booking]): F[Map[ItemId, Int]] =
    bookings.foldLeftM(Map.empty[ItemId, Int]) { (acc, b) =>
      b.items.foldLeftM(acc) {
        case (m, BookedIndividualItem(itemId, qty)) =>
          m.updated(itemId, m.getOrElse(itemId, 0) + qty).pure[F]
        case (m, BookedCombo(comboId, comboQty)) =>
          comboRepo.findById(comboId).map {
            case Some(combo) =>
              combo.items.foldLeft(m) { (acc2, cd) =>
                val added = cd.quantity * comboQty
                acc2.updated(cd.itemId, acc2.getOrElse(cd.itemId, 0) + added)
              }
            case None => m
          }
      }
    }

  // Resolve the requested id as either an Item or a Combo and check availability.
  // Returns the unavailable entries contributed by this id (empty == ok).
  private def checkEntry(
    requestedId: ItemId,
    qty:         Int,
    consumption: Map[ItemId, Int]
  ): F[List[UnavailableItem]] =
    itemRepo.findById(requestedId).flatMap {
      case Some(item) => checkItem(requestedId, item, qty, consumption).pure[F]
      case None       => tryCombo(requestedId, qty, consumption)
    }

  private def checkItem(
    requestedId: ItemId,
    item:        Item,
    qty:         Int,
    consumption: Map[ItemId, Int]
  ): List[UnavailableItem] =
    val effective = item.stock - consumption.getOrElse(item.id, 0)
    if effective >= qty then Nil
    else List(UnavailableItem(requestedId, AvailabilityReason.StockDepleted))

  private def tryCombo(
    requestedId: ItemId,
    qty:         Int,
    consumption: Map[ItemId, Int]
  ): F[List[UnavailableItem]] =
    ComboId.fromString(requestedId.value) match
      case Left(_) =>
        // Not a valid id format at all — treat as combo-item missing for the caller.
        List(UnavailableItem(requestedId, AvailabilityReason.ComboItemMissing)).pure[F]
      case Right(comboId) =>
        comboRepo.findById(comboId).flatMap {
          case None        => List(UnavailableItem(requestedId, AvailabilityReason.ComboItemMissing)).pure[F]
          case Some(combo) => checkComboConstituents(requestedId, combo, qty, consumption)
        }

  // A combo is available when every constituent has effective stock >=
  // (constituent.quantity * requestedComboQty). On failure, the unavailable
  // entry is keyed on the *combo's* id (what the caller requested).
  private def checkComboConstituents(
    requestedId: ItemId,
    combo:       Combo,
    qty:         Int,
    consumption: Map[ItemId, Int]
  ): F[List[UnavailableItem]] =
    combo.items
      .traverse { cd =>
        val needed = cd.quantity * qty
        itemRepo.findById(cd.itemId).map {
          case None       => false               // missing constituent → unavailable
          case Some(item) => (item.stock - consumption.getOrElse(item.id, 0)) >= needed
        }
      }
      .map { allOk =>
        if allOk.forall(identity) then Nil
        else List(UnavailableItem(requestedId, AvailabilityReason.ComboItemMissing))
      }
