package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all._
import com.locarya.adapters.persistence.Database
import doobie.Transactor
import org.http4s._
import org.http4s.dsl.Http4sDsl

object HealthEndpoints {

  def routes[F[_]: Async](xa: Transactor[F]): HttpRoutes[F] = {
    val dsl = Http4sDsl[F]
    import dsl._

    HttpRoutes.of[F] {
      case GET -> Root / "health" / "live" =>
        Ok()

      case GET -> Root / "health" / "ready" =>
        Database.checkHealth(xa).flatMap { healthy =>
          if (healthy) Ok() else ServiceUnavailable()
        }
    }
  }
}
