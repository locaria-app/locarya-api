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
    items: List[(ItemId, Int)],
    date: LocalDate,
    excludeBookingId: Option[BookingId]
  ): F[AvailabilityResult] =
    for
      consumed   <- buildConsumedMap(date, excludeBookingId)
      perRequest <- items.traverse { case (id, qty) => evaluateRequested(id, qty, consumed) }
      unavailable = perRequest.flatten
      result      = if unavailable.isEmpty then AvailabilityResult.available
                    else AvailabilityResult.unavailable(unavailable)
      _          <- Logger[F].info(buildLogLine(items, date, result))
    yield result

  private def buildConsumedMap(
    date:             LocalDate,
    excludeBookingId: Option[BookingId]
  ): F[Map[ItemId, Int]] =
    for
      bookings <- bookingRepo.findByDateRange(date, date)
      active    = bookings.filter { b =>
                    (b.status == BookingStatus.Confirmed || b.status == BookingStatus.InProgress) &&
                    excludeBookingId.forall(_ != b.id)
                  }
      pairs    <- active.flatTraverse(decomposeBooking)
    yield pairs.groupMapReduce(_._1)(_._2)(_ + _)

  private def decomposeBooking(booking: Booking): F[List[(ItemId, Int)]] =
    booking.items.flatTraverse {
      case BookedIndividualItem(id, qty) =>
        List((id, qty)).pure[F]
      case BookedCombo(comboId, qty) =>
        comboRepo.findById(comboId).map {
          case Some(combo) => combo.items.map(d => (d.itemId, d.quantity * qty))
          case None        => List.empty
        }
    }

  private def evaluateRequested(
    requestedId: ItemId,
    qty:         Int,
    consumed:    Map[ItemId, Int]
  ): F[List[UnavailableItem]] =
    itemRepo.findById(requestedId).flatMap {
      case Some(item) =>
        if hasStock(item, qty, consumed) then List.empty[UnavailableItem].pure[F]
        else List(UnavailableItem(requestedId, "stock depleted")).pure[F]
      case None =>
        ComboId.fromString(requestedId.value) match
          case Right(comboId) =>
            comboRepo.findById(comboId).flatMap {
              case Some(combo) => evaluateCombo(requestedId, combo, qty, consumed)
              case None        => List(UnavailableItem(requestedId, "item not found")).pure[F]
            }
          case Left(_) =>
            List(UnavailableItem(requestedId, "item not found")).pure[F]
    }

  private def evaluateCombo(
    requestedId: ItemId,
    combo:       Combo,
    qty:         Int,
    consumed:    Map[ItemId, Int]
  ): F[List[UnavailableItem]] =
    combo.items.traverse { definition =>
      itemRepo.findById(definition.itemId).map {
        case Some(item) =>
          val needed = definition.quantity * qty
          hasStockFor(item, needed, consumed)
        case None =>
          false
      }
    }.map { fits =>
      if fits.forall(identity) then List.empty[UnavailableItem]
      else List(UnavailableItem(requestedId, "combo item missing"))
    }

  private def hasStock(item: Item, qty: Int, consumed: Map[ItemId, Int]): Boolean =
    hasStockFor(item, qty, consumed)

  private def hasStockFor(item: Item, needed: Int, consumed: Map[ItemId, Int]): Boolean =
    item.stock - consumed.getOrElse(item.id, 0) >= needed

  private def buildLogLine(
    items:  List[(ItemId, Int)],
    date:   LocalDate,
    result: AvailabilityResult
  ): String =
    val itemIds = items.map { case (id, _) => s""""${id.value}"""" }.mkString(",")
    val reasons = result.unavailableItems.map(u => s""""${u.reason}"""").distinct.mkString(",")
    s"""{"event":"AvailabilityChecked","itemIds":[$itemIds],"date":"$date","available":${result.available},"reasons":[$reasons]}"""
