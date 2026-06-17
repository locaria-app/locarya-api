package com.locarya.domain.services

import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import java.time.LocalDate
import org.typelevel.log4cats.Logger

/** A requested id resolved against the catalog. Inactive items/combos are dropped to
  * `Unresolved` so a soft-deleted id is never reported as bookable.
  */
private enum ResolvedRequest:
  case AnItem(requestedId: ItemId, item: Item, qty: Int)
  // parts: (constituentId, the active item if it exists, perComboQty)
  case ACombo(requestedId: ItemId, parts: List[(ItemId, Option[Item], Int)], qty: Int)
  case Unresolved(requestedId: ItemId)

class AvailabilityServiceImpl[F[_]: Sync: Logger](
  itemRepo:    ItemRepository[F],
  comboRepo:   ComboRepository[F],
  bookingRepo: BookingRepository[F]
) extends AvailabilityService[F]:

  import ResolvedRequest.*

  def checkAvailability(
    items:            List[(ItemId, Int)],
    date:             LocalDate,
    excludeBookingId: Option[BookingId]
  ): F[List[ItemAvailability]] =
    for
      consumed <- buildConsumedMap(date, excludeBookingId)
      resolved <- items.traverse { case (id, qty) => resolve(id, qty) }
      // Total demand the whole request places on each underlying item — so sibling
      // entries (duplicate ids, or a combo plus one of its own constituents) compete
      // for the same stock instead of each seeing the full pool.
      demand    = buildDemand(resolved)
      result    = resolved.map(r => toAvailability(r, consumed, demand))
      _        <- Logger[F].info(buildLogLine(date, result))
    yield result

  /** Stock consumed on `date` by Confirmed/InProgress bookings, keyed by the underlying
    * ItemId. A booked Combo is decomposed into its constituent items so it blocks the
    * items underneath. `excludeBookingId` drops one booking (the edit-booking flow).
    */
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

  /** Resolve a requested id to an active Item or Combo. Both the requested id and a
    * combo's constituents are filtered by `isActive`: a soft-deleted id is not bookable,
    * and a combo with a deactivated constituent cannot be fulfilled.
    */
  private def resolve(requestedId: ItemId, qty: Int): F[ResolvedRequest] =
    itemRepo.findById(requestedId).flatMap {
      case Some(item) if item.isActive => AnItem(requestedId, item, qty).pure[F]
      case Some(_)                     => Unresolved(requestedId).pure[F]
      case None =>
        ComboId.fromString(requestedId.value) match
          case Right(comboId) =>
            comboRepo.findById(comboId).flatMap {
              case Some(combo) if combo.isActive =>
                combo.items
                  .traverse(d => itemRepo.findById(d.itemId).map(opt => (d.itemId, opt.filter(_.isActive), d.quantity)))
                  .map(parts => ACombo(requestedId, parts, qty))
              case _ => Unresolved(requestedId).pure[F]
            }
          case Left(_) => Unresolved(requestedId).pure[F]
    }

  private def buildDemand(resolved: List[ResolvedRequest]): Map[ItemId, Int] =
    resolved.flatMap {
      case AnItem(_, item, qty)   => List(item.id -> qty)
      case ACombo(_, parts, qty)  => parts.collect { case (_, Some(item), per) => item.id -> per * qty }
      case Unresolved(_)          => Nil
    }.groupMapReduce(_._1)(_._2)(_ + _)

  /** `availableQty` is the entry's standalone capacity (remaining stock for an Item; max
    * fulfillable combos for a Combo). `available` is request-aware: the whole request's
    * demand on each underlying item must fit within the booking-adjusted remaining stock.
    */
  private def toAvailability(
    r:        ResolvedRequest,
    consumed: Map[ItemId, Int],
    demand:   Map[ItemId, Int]
  ): ItemAvailability =
    r match
      case AnItem(requestedId, item, _) =>
        val availableQty = remaining(item, consumed)
        val fits         = demand.getOrElse(item.id, 0) <= availableQty
        ItemAvailability(requestedId, AvailabilityKind.Item, fits, availableQty)
      case ACombo(requestedId, parts, _) =>
        val perConstituent = parts.map { case (_, opt, per) => opt.fold(0)(remaining(_, consumed) / per) }
        val availableQty   = if perConstituent.isEmpty then 0 else perConstituent.min
        val fits           = parts.forall { case (_, opt, _) =>
                               opt.exists(item => demand.getOrElse(item.id, 0) <= remaining(item, consumed))
                             }
        ItemAvailability(requestedId, AvailabilityKind.Combo, fits, availableQty)
      case Unresolved(requestedId) =>
        ItemAvailability(requestedId, AvailabilityKind.Unknown, available = false, availableQty = 0)

  /** Remaining stock for an item on the date, clamped at 0 (never reports negative). */
  private def remaining(item: Item, consumed: Map[ItemId, Int]): Int =
    (item.stock - consumed.getOrElse(item.id, 0)).max(0)

  private def buildLogLine(date: LocalDate, result: List[ItemAvailability]): String =
    val itemIds        = result.map(r => s""""${r.id.value}"""").mkString(",")
    val allAvailable   = result.forall(_.available)
    val unavailableIds = result.filterNot(_.available).map(r => s""""${r.id.value}"""").mkString(",")
    s"""{"event":"AvailabilityChecked","itemIds":[$itemIds],"date":"$date","available":$allAvailable,"unavailableIds":[$unavailableIds]}"""
