package com.locarya.services

import com.locarya.core.domain.{Customer, CustomerId, Email}

trait CustomerRepo[F[_]] extends Repository[F, Customer, CustomerId] {
  def findByEmail(email: Email): F[Option[Customer]]
}
