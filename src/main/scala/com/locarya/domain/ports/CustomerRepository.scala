package com.locarya.domain.ports

import com.locarya.domain.models.*

trait CustomerRepository[F[_]] extends Repository[F, Customer, CustomerId]:
  def findByEmail(email: Email): F[Option[Customer]]
