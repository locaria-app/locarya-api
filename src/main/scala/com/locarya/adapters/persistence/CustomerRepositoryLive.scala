package com.locarya.adapters.persistence

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.CustomerRepository
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

final class CustomerRepositoryLive[F[_]: Async] private (xa: Transactor[F])
    extends CustomerRepository[F]:

  private case class CustomerRow(
    id:    UUID,
    email: String,
    cpf:   Option[String],
    name:  String,
    phone: Option[String]
  ) derives Read

  private def rowToCustomer(row: CustomerRow): F[Customer] =
    (for
      id    <- CustomerId.fromString(row.id.toString)
      email <- Email.fromString(row.email)
      cpf   <- row.cpf.traverse(CPF.fromString)
      c     <- Customer.create(id, email, cpf, row.name, row.phone)
    yield c).fold(
      err => Async[F].raiseError(new RuntimeException(s"DB→domain mapping failed: $err")),
      _.pure[F]
    )

  private val selectBase = fr"SELECT id, email, cpf, name, phone FROM customers"

  override def create(customer: Customer): F[Customer] =
    sql"""INSERT INTO customers (id, email, cpf, name, phone)
          VALUES (${UUID.fromString(customer.id.value)},
                  ${customer.email.value},
                  ${customer.cpf.map(_.value)},
                  ${customer.name},
                  ${customer.phone})"""
      .update.run.transact(xa).adaptError {
        case ex: org.postgresql.util.PSQLException if ex.getSQLState == "23505" =>
          val constraint = Option(ex.getServerErrorMessage).flatMap(m => Option(m.getConstraint)).getOrElse("")
          if constraint == "customers_email_key" then CustomerError.DuplicateEmail(customer.email.value)
          else customer.cpf.fold[CustomerError](CustomerError.DuplicateEmail(customer.email.value))(c => CustomerError.DuplicateCpf(c.value))
      } >> customer.pure[F]

  override def findById(id: CustomerId): F[Option[Customer]] =
    val uuid = UUID.fromString(id.value)
    (selectBase ++ fr"WHERE id = $uuid")
      .query[CustomerRow]
      .option
      .transact(xa)
      .flatMap(_.traverse(rowToCustomer))

  override def update(customer: Customer): F[Customer] =
    sql"""UPDATE customers SET
            email      = ${customer.email.value},
            cpf        = ${customer.cpf.map(_.value)},
            name       = ${customer.name},
            phone      = ${customer.phone},
            updated_at = NOW()
          WHERE id = ${UUID.fromString(customer.id.value)}"""
      .update.run.transact(xa) >> customer.pure[F]

  override def findByEmail(email: Email): F[Option[Customer]] =
    (selectBase ++ fr"WHERE email = ${email.value}")
      .query[CustomerRow]
      .option
      .transact(xa)
      .flatMap(_.traverse(rowToCustomer))

  override def findByIds(ids: List[CustomerId]): F[Map[CustomerId, Customer]] =
    cats.data.NonEmptyList.fromList(ids) match
      case None      => Map.empty.pure[F]
      case Some(nel) =>
        val uuids = nel.map(id => UUID.fromString(id.value))
        (selectBase ++ fr"WHERE id = ANY(${uuids.toList.toArray})")
          .query[CustomerRow]
          .to[List]
          .transact(xa)
          .flatMap(_.traverse(rowToCustomer))
          .map(customers => customers.map(c => c.id -> c).toMap)

object CustomerRepositoryLive:
  def make[F[_]: Async](xa: Transactor[F]): CustomerRepository[F] =
    new CustomerRepositoryLive[F](xa)
