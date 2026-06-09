package com.locarya

import cats.effect._
import cats.syntax.all._
import com.comcast.ip4s._
import com.locarya.adapters.http.HealthEndpoints
import com.locarya.adapters.http.middleware.CorrelationIdMiddleware
import com.locarya.config.AppConfig
import com.locarya.adapters.persistence.Database
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

object Main extends IOApp.Simple {

  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]

  def run: IO[Unit] = {
    val logger = loggerFactory.getLogger

    (for {
      config <- AppConfig.load[IO]
      _ <- Resource.eval(logger.info("Starting Locarya API"))

      xa <- Database.transactor[IO](
        config.database.url,
        config.database.user,
        config.database.password
      )

      routes = CorrelationIdMiddleware(HealthEndpoints.routes[IO](xa))

      server <- EmberServerBuilder
        .default[IO]
        .withHost(Host.fromString(config.http.host).getOrElse(host"0.0.0.0"))
        .withPort(Port.fromInt(config.http.port).getOrElse(port"8080"))
        .withHttpApp(routes.orNotFound)
        .build

      _ <- Resource.eval(logger.info(s"Server started at http://${config.http.host}:${config.http.port}"))
    } yield server).useForever
  }
}
