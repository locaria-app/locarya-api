package com.locarya.infrastructure.repository

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.core.domain.*
import com.locarya.services.CustomerRepo
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import DoobieMeta.given

class CustomerRepoImpl[F[_]: Async](xa: Transactor[F]) extends CustomerRepo[F] {

  override def create(customer: Customer): F[Customer] = {
    sql"""
      INSERT INTO customers (id, email, cpf, name)
      VALUES (${customer.id}, ${customer.email}, ${customer.cpf}, ${customer.name})
    """.update.run.transact(xa).as(customer)
  }

  override def findById(id: CustomerId): F[Option[Customer]] = {
    sql"""
      SELECT id, email, cpf, name
      FROM customers
      WHERE id = $id
    """.query[(CustomerId, Email, CPF, String)]
      .option
      .transact(xa)
      .map(_.map { case (id, email, cpf, name) =>
        Customer.create(id, email, cpf, name)
          .fold(e => throw new Exception(e.toString), identity)
      })
  }

  override def findByEmail(email: Email): F[Option[Customer]] = {
    sql"""
      SELECT id, email, cpf, name
      FROM customers
      WHERE email = $email
    """.query[(CustomerId, Email, CPF, String)]
      .option
      .transact(xa)
      .map(_.map { case (id, email, cpf, name) =>
        Customer.create(id, email, cpf, name)
          .fold(e => throw new Exception(e.toString), identity)
      })
  }

  override def update(customer: Customer): F[Customer] = {
    sql"""
      UPDATE customers
      SET email = ${customer.email},
          cpf = ${customer.cpf},
          name = ${customer.name},
          updated_at = NOW()
      WHERE id = ${customer.id}
    """.update.run.transact(xa).as(customer)
  }
}
