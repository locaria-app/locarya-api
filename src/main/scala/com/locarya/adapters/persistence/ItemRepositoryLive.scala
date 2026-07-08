package com.locarya.adapters.persistence

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.ItemRepository
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

final class ItemRepositoryLive[F[_]: Async] private (xa: Transactor[F])
    extends ItemRepository[F]:

  private case class ItemRow(
    id:              UUID,
    providerId:      UUID,
    name:            String,
    description:     String,
    dailyRate:       BigDecimal,
    stock:           Int,
    requiresMonitor: Boolean,
    isActive:        Boolean
  ) derives Read

  private def rowToItem(row: ItemRow): F[Item] =
    (for
      id    <- ItemId.fromString(row.id.toString)
      pid   <- ProviderId.fromString(row.providerId.toString)
      money <- Money.fromAmount(row.dailyRate)
      item  <- Item.create(id, pid, row.name, row.description, money, row.stock, row.requiresMonitor, row.isActive)
    yield item).fold(
      err => Async[F].raiseError(new RuntimeException(s"DB→domain mapping failed: $err")),
      _.pure[F]
    )

  private val selectBase =
    fr"""SELECT id, provider_id, name, description, daily_rate, stock, requires_monitor, is_active
         FROM items"""

  override def create(item: Item): F[Item] =
    sql"""INSERT INTO items
            (id, provider_id, name, description, daily_rate, stock, requires_monitor, is_active)
          VALUES
            (${UUID.fromString(item.id.value)}, ${UUID.fromString(item.providerId.value)},
             ${item.name}, ${item.description}, ${item.dailyRate.amount},
             ${item.stock}, ${item.requiresMonitor}, ${item.isActive})"""
      .update.run.transact(xa) >> item.pure[F]

  override def findById(id: ItemId): F[Option[Item]] =
    (selectBase ++ fr"WHERE id = ${UUID.fromString(id.value)}")
      .query[ItemRow].option.transact(xa).flatMap(_.traverse(rowToItem))

  override def update(item: Item): F[Item] =
    sql"""UPDATE items SET
            name            = ${item.name},
            description     = ${item.description},
            daily_rate      = ${item.dailyRate.amount},
            stock           = ${item.stock},
            requires_monitor = ${item.requiresMonitor},
            is_active       = ${item.isActive},
            updated_at      = NOW()
          WHERE id = ${UUID.fromString(item.id.value)}"""
      .update.run.transact(xa) >> item.pure[F]

  override def findByProviderId(providerId: ProviderId): F[List[Item]] =
    (selectBase ++ fr"WHERE provider_id = ${UUID.fromString(providerId.value)}")
      .query[ItemRow].to[List].transact(xa).flatMap(_.traverse(rowToItem))

  override def findActiveByProviderId(providerId: ProviderId): F[List[Item]] =
    (selectBase ++ fr"WHERE provider_id = ${UUID.fromString(providerId.value)} AND is_active = TRUE")
      .query[ItemRow].to[List].transact(xa).flatMap(_.traverse(rowToItem))

object ItemRepositoryLive:
  def make[F[_]: Async](xa: Transactor[F]): ItemRepository[F] =
    new ItemRepositoryLive[F](xa)
