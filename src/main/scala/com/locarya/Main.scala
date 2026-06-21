package com.locarya

import cats.effect._
import cats.syntax.all._
import com.comcast.ip4s._
import com.locarya.adapters.http.{AttendantRoutes, AuthRoutes, AvailabilityRoutes, DashboardBookingRoutes, HealthEndpoints, ItemRoutes, PaymentRoutes, StorefrontBookingRoutes, StorefrontRoutes, SwaggerRoutes}
import com.locarya.adapters.http.middleware.CorrelationIdMiddleware
import com.locarya.adapters.persistence.{Database, ProviderRepositoryLive}
import com.locarya.config.AppConfig
import com.locarya.domain.services.{AttendantServiceImpl, AuthServiceImpl, PaymentServiceImpl, ProviderServiceImpl}
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

object Main extends IOApp.Simple {

  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]

  def run: IO[Unit] = {
    implicit val logger: Logger[IO] = loggerFactory.getLogger

    (for {
      config <- AppConfig.load[IO]
      _ <- Resource.eval(logger.info("Starting Locarya API"))

      xa <- Database.transactor[IO](
        config.database.url,
        config.database.user,
        config.database.password
      )

      providerRepo    = ProviderRepositoryLive.make[IO](xa)
      providerService = ProviderServiceImpl[IO](providerRepo)
      authService     = AuthServiceImpl[IO](providerRepo, config.jwt.secret)

      swaggerEnabled = sys.env.getOrElse("SWAGGER_ENABLED", "false") == "true"
      docsRoute      = SwaggerRoutes.maybeDocsRoute[IO](
                         ItemRoutes.allEndpoints ++
                         AuthRoutes.allEndpoints ++
                         StorefrontRoutes.allEndpoints ++
                         AvailabilityRoutes.allEndpoints ++
                         StorefrontBookingRoutes.allEndpoints ++
                         DashboardBookingRoutes.allEndpoints,
                         swaggerEnabled
                       )

      // StorefrontRoutes, AvailabilityRoutes, StorefrontBookingRoutes, ItemRoutes — pending
      // live repository implementations (ItemRepositoryLive, BookingRepositoryLive, etc.)
      routes = CorrelationIdMiddleware(
                 HealthEndpoints.routes[IO](xa) <+>
                 AuthRoutes.routes[IO](providerService, authService) <+>
                 docsRoute.getOrElse(HttpRoutes.empty[IO])
               )

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
