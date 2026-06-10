package com.locarya.domain.ports

trait Repository[F[_], E, ID]:
  def create(entity: E): F[E]
  def findById(id: ID): F[Option[E]]
  def update(entity: E): F[E]
