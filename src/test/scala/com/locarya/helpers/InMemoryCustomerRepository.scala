package com.locarya.helpers

import cats.effect.Async
import cats.effect.Ref
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.CustomerRepository

final class InMemoryCustomerRepository[F[_]: Async] private (
  state: Ref[F, Map[CustomerId, Customer]]
) extends CustomerRepository[F]:

  def create(customer: Customer): F[Customer] =
    state.modify { store =>
      if store.contains(customer.id) then
        (store, new RuntimeException(s"Customer ${customer.id.value} already exists").raiseError[F, Customer])
      else
        (store + (customer.id -> customer), customer.pure[F])
    }.flatten

  def findById(id: CustomerId): F[Option[Customer]] =
    state.get.map(_.get(id))

  def update(customer: Customer): F[Customer] =
    state.modify(store => (store + (customer.id -> customer)) -> customer)

  def findByEmail(email: Email): F[Option[Customer]] =
    state.get.map(_.values.find(_.email == email))

object InMemoryCustomerRepository:
  def make[F[_]: Async]: F[InMemoryCustomerRepository[F]] =
    Ref.of[F, Map[CustomerId, Customer]](Map.empty).map(new InMemoryCustomerRepository(_))
