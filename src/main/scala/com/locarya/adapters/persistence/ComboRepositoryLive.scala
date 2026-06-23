package com.locarya.adapters.persistence

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.ComboRepository
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

final class ComboRepositoryLive[F[_]: Async] private (xa: Transactor[F])
    extends ComboRepository[F]:

  private case class ComboRow(
    id:                   UUID,
    providerId:           UUID,
    name:                 String,
    description:          String,
    dailyRate:            BigDecimal,
    attendantRequirement: String,
    isActive:             Boolean
  ) derives Read

  private case class ComboItemRow(comboId: UUID, itemId: UUID, quantity: Int) derives Read

  private case class ComboWithItemRow(
    id:                   UUID,
    providerId:           UUID,
    name:                 String,
    description:          String,
    dailyRate:            BigDecimal,
    attendantRequirement: String,
    isActive:             Boolean,
    itemId:               Option[UUID],
    quantity:             Option[Int]
  ) derives Read

  private def parseRequirement(s: String): Either[ValidationError, AttendantRequirement] =
    s match
      case "REQUIRED"    => Right(AttendantRequirement.Required)
      case "OPTIONAL"    => Right(AttendantRequirement.Optional)
      case "NOT_ALLOWED" => Right(AttendantRequirement.NotAllowed)
      case other         => Left(InvalidAttendantRequirement(s"Unknown attendant_requirement value: $other"))

  private def reqStr(req: AttendantRequirement): String = req match
    case AttendantRequirement.Required   => "REQUIRED"
    case AttendantRequirement.Optional   => "OPTIONAL"
    case AttendantRequirement.NotAllowed => "NOT_ALLOWED"

  private def rowToCombo(row: ComboRow, items: List[ComboItemRow]): F[Combo] =
    (for
      id    <- ComboId.fromString(row.id.toString)
      pid   <- ProviderId.fromString(row.providerId.toString)
      money <- Money.fromAmount(row.dailyRate)
      req   <- parseRequirement(row.attendantRequirement)
      defs  <- items.traverse(r => ItemId.fromString(r.itemId.toString).map(ComboItemDefinition(_, r.quantity)))
      combo <- Combo.create(id, pid, row.name, row.description, money, defs, req, row.isActive)
    yield combo).fold(
      err => Async[F].raiseError(new RuntimeException(s"DB→domain mapping failed: $err")),
      _.pure[F]
    )

  override def create(combo: Combo): F[Combo] =
    val comboUuid = UUID.fromString(combo.id.value)
    val insertCombo =
      sql"""INSERT INTO combos
              (id, provider_id, name, description, daily_rate, attendant_requirement, is_active)
            VALUES
              ($comboUuid, ${UUID.fromString(combo.providerId.value)},
               ${combo.name}, ${combo.description},
               ${combo.dailyRate.amount}, ${reqStr(combo.attendantRequirement)}, ${combo.isActive})"""
        .update.run

    val insertItems = combo.items.traverse_ { item =>
      sql"""INSERT INTO combo_items (id, combo_id, item_id, quantity)
            VALUES (${UUID.randomUUID()}, $comboUuid, ${UUID.fromString(item.itemId.value)}, ${item.quantity})"""
        .update.run
    }

    (insertCombo >> insertItems).transact(xa) >> combo.pure[F]

  override def findById(id: ComboId): F[Option[Combo]] =
    val comboUuid = UUID.fromString(id.value)
    val fetchCombo =
      sql"""SELECT id, provider_id, name, description, daily_rate, attendant_requirement, is_active
            FROM combos WHERE id = $comboUuid"""
        .query[ComboRow].option
    val fetchItems =
      sql"""SELECT combo_id, item_id, quantity FROM combo_items WHERE combo_id = $comboUuid"""
        .query[ComboItemRow].to[List]
    (for
      comboOpt <- fetchCombo
      items    <- fetchItems
    yield (comboOpt, items)).transact(xa).flatMap {
      case (Some(row), items) => rowToCombo(row, items).map(Some(_))
      case (None, _)          => None.pure[F]
    }

  override def findItemsInCombo(comboId: ComboId): F[List[ComboItemDefinition]] =
    val comboUuid = UUID.fromString(comboId.value)
    sql"""SELECT combo_id, item_id, quantity FROM combo_items WHERE combo_id = $comboUuid"""
      .query[ComboItemRow].to[List].transact(xa).flatMap {
        _.traverse { r =>
          ItemId.fromString(r.itemId.toString)
            .map(ComboItemDefinition(_, r.quantity))
            .fold(
              err => Async[F].raiseError(new RuntimeException(s"DB→domain mapping failed: $err")),
              _.pure[F]
            )
        }
      }

  override def findActiveByProviderId(providerId: ProviderId): F[List[Combo]] =
    val providerUuid = UUID.fromString(providerId.value)
    sql"""SELECT c.id, c.provider_id, c.name, c.description, c.daily_rate, c.attendant_requirement, c.is_active,
                 ci.item_id, ci.quantity
          FROM combos c
          LEFT JOIN combo_items ci ON ci.combo_id = c.id
          WHERE c.provider_id = $providerUuid AND c.is_active = TRUE"""
      .query[ComboWithItemRow].to[List].transact(xa).flatMap { rows =>
        rows.groupBy(_.id).toList.traverse { case (_, comboRows) =>
          val head = comboRows.head
          val itemRows = comboRows.collect {
            case r if r.itemId.isDefined =>
              ComboItemRow(head.id, r.itemId.get, r.quantity.getOrElse(0))
          }
          rowToCombo(
            ComboRow(head.id, head.providerId, head.name, head.description,
              head.dailyRate, head.attendantRequirement, head.isActive),
            itemRows
          )
        }
      }

  override def update(combo: Combo): F[Combo] =
    val comboUuid = UUID.fromString(combo.id.value)
    (for
      _ <- sql"""UPDATE combos SET
                   name                  = ${combo.name},
                   description           = ${combo.description},
                   daily_rate            = ${combo.dailyRate.amount},
                   attendant_requirement = ${reqStr(combo.attendantRequirement)},
                   is_active             = ${combo.isActive},
                   updated_at            = NOW()
                 WHERE id = $comboUuid""".update.run
      _ <- sql"DELETE FROM combo_items WHERE combo_id = $comboUuid".update.run
      _ <- combo.items.traverse_ { item =>
             sql"""INSERT INTO combo_items (id, combo_id, item_id, quantity)
                   VALUES (${UUID.randomUUID()}, $comboUuid, ${UUID.fromString(item.itemId.value)}, ${item.quantity})"""
               .update.run
           }
    yield combo).transact(xa)

object ComboRepositoryLive:
  def make[F[_]: Async](xa: Transactor[F]): ComboRepository[F] =
    new ComboRepositoryLive[F](xa)
