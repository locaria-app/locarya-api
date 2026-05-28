package com.locarya.infrastructure.db

import cats.effect._
import cats.syntax.all._
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._

import scala.concurrent.ExecutionContext

object Database {

  def transactor[F[_]: Async](
    url: String,
    user: String,
    password: String
  ): Resource[F, Transactor[F]] = {
    HikariTransactor.newHikariTransactor[F](
      driverClassName = "org.postgresql.Driver",
      url = url,
      user = user,
      pass = password,
      connectEC = ExecutionContext.global
    )
  }

  def checkHealth[F[_]: Async](xa: Transactor[F]): F[Boolean] = {
    sql"SELECT 1".query[Int].unique.transact(xa).attempt.map(_.isRight)
  }
}
