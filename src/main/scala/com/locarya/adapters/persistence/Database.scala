package com.locarya.adapters.persistence

import cats.effect._
import cats.syntax.all._
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.util.ExecutionContexts

object Database {

  def transactor[F[_]: Async](
    url: String,
    user: String,
    password: String
  ): Resource[F, Transactor[F]] =
    for {
      ec <- ExecutionContexts.fixedThreadPool[F](32)
      xa <- HikariTransactor.newHikariTransactor[F](
              driverClassName = "org.postgresql.Driver",
              url  = url,
              user = user,
              pass = password,
              connectEC = ec
            )
      _ <- Resource.eval(sql"SELECT 1".query[Int].unique.transact(xa).void)
    } yield xa

  def checkHealth[F[_]: Async](xa: Transactor[F]): F[Boolean] = {
    sql"SELECT 1".query[Int].unique.transact(xa).attempt.map(_.isRight)
  }
}
