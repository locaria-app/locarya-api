package com.locarya.infrastructure.repository

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.core.domain.*
import com.locarya.services.ItemRepo
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import DoobieMeta.given

class ItemRepoImpl[F[_]: Async](xa: Transactor[F]) extends ItemRepo[F] {

  override def create(item: Item): F[Item] = {
    sql"""
      INSERT INTO items (id, provider_id, name, description, daily_rate, stock, attendant_requirement)
      VALUES (${item.id}, ${item.providerId}, ${item.name}, ${item.description}, ${item.dailyRate}, ${item.stock}, ${item.attendantRequirement})
    """.update.run.transact(xa).as(item)
  }

  override def findById(id: ItemId): F[Option[Item]] = {
    sql"""
      SELECT id, provider_id, name, description, daily_rate, stock, attendant_requirement
      FROM items
      WHERE id = $id
    """.query[(ItemId, ProviderId, String, String, Money, Int, AttendantRequirement)]
      .option
      .transact(xa)
      .map(_.map { case (id, providerId, name, description, dailyRate, stock, attendantRequirement) =>
        Item.create(id, providerId, name, description, dailyRate, stock, attendantRequirement)
          .fold(e => throw new Exception(e.toString), identity)
      })
  }

  override def findByProviderId(providerId: ProviderId): F[List[Item]] = {
    sql"""
      SELECT id, provider_id, name, description, daily_rate, stock, attendant_requirement
      FROM items
      WHERE provider_id = $providerId
    """.query[(ItemId, ProviderId, String, String, Money, Int, AttendantRequirement)]
      .to[List]
      .transact(xa)
      .map(_.map { case (id, providerId, name, description, dailyRate, stock, attendantRequirement) =>
        Item.create(id, providerId, name, description, dailyRate, stock, attendantRequirement)
          .fold(e => throw new Exception(e.toString), identity)
      })
  }

  override def update(item: Item): F[Item] = {
    sql"""
      UPDATE items
      SET provider_id = ${item.providerId},
          name = ${item.name},
          description = ${item.description},
          daily_rate = ${item.dailyRate},
          stock = ${item.stock},
          attendant_requirement = ${item.attendantRequirement},
          updated_at = NOW()
      WHERE id = ${item.id}
    """.update.run.transact(xa).as(item)
  }
}
