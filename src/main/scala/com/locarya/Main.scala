package com.locarya

import cats.effect._
import cats.syntax.all._
import com.comcast.ip4s._
import org.flywaydb.core.Flyway
import com.locarya.adapters.http.{AttendantRoutes, AuthRoutes, AvailabilityRoutes, ComboRoutes, DashboardBookingRoutes, DashboardProviderRoutes, HealthEndpoints, ItemRoutes, PaymentRoutes, StorefrontBookingRoutes, StorefrontRoutes, SwaggerRoutes}
import com.locarya.adapters.http.middleware.CorrelationIdMiddleware
import com.locarya.adapters.persistence.{AttendantRepositoryLive, BookingRepositoryLive, ComboRepositoryLive, CustomerRepositoryLive, Database, ItemImageRepositoryLive, ItemRepositoryLive, PaymentRepositoryLive, ProviderRepositoryLive}
import com.locarya.config.AppConfig
import com.locarya.domain.services.{AttendantServiceImpl, AuthServiceImpl, AvailabilityServiceImpl, BookingServiceImpl, ComboServiceImpl, ItemServiceImpl, PaymentServiceImpl, ProviderServiceImpl, StorefrontServiceImpl}
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.http4s.server.middleware.{Logger => HttpLogger}
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

      _ <- Resource.eval(IO.blocking {
             Flyway.configure()
               .dataSource(config.database.url, config.database.user, config.database.password)
               .locations("classpath:db/migration")
               .load()
               .migrate()
           }.flatMap(r => logger.info(s"Flyway: ${r.migrationsExecuted} migration(s) applied")))

      xa <- Database.transactor[IO](
        config.database.url,
        config.database.user,
        config.database.password
      )

      providerRepo        = ProviderRepositoryLive.make[IO](xa)
      itemRepo            = ItemRepositoryLive.make[IO](xa)
      itemImageRepo       = ItemImageRepositoryLive.make[IO](xa)
      comboRepo           = ComboRepositoryLive.make[IO](xa)
      attendantRepo       = AttendantRepositoryLive.make[IO](xa)
      customerRepo        = CustomerRepositoryLive.make[IO](xa)
      bookingRepo         = BookingRepositoryLive.make[IO](xa)
      paymentRepo         = PaymentRepositoryLive.make[IO](xa)
      providerService     = ProviderServiceImpl[IO](providerRepo)
      authService         = AuthServiceImpl[IO](providerRepo, config.jwt.secret)
      availabilityService = AvailabilityServiceImpl[IO](itemRepo, comboRepo, bookingRepo)
      bookingService      = BookingServiceImpl[IO](providerRepo, customerRepo, bookingRepo, itemRepo, comboRepo, availabilityService, attendantRepo)
      itemService         = ItemServiceImpl[IO](itemRepo, itemImageRepo, bookingRepo)
      comboService        = ComboServiceImpl[IO](comboRepo, itemRepo, bookingRepo)
      attendantService    = AttendantServiceImpl[IO](attendantRepo, bookingRepo)
      paymentService      = PaymentServiceImpl[IO](bookingRepo, paymentRepo)
      storefrontService   = StorefrontServiceImpl[IO](providerRepo, itemRepo, itemImageRepo, comboRepo)

      swaggerEnabled = sys.env.getOrElse("SWAGGER_ENABLED", "false") == "true"
      docsRoute      = SwaggerRoutes.maybeDocsRoute[IO](
                         ItemRoutes.allEndpoints ++
                         AuthRoutes.allEndpoints ++
                         StorefrontRoutes.allEndpoints ++
                         AvailabilityRoutes.allEndpoints ++
                         StorefrontBookingRoutes.allEndpoints ++
                         DashboardBookingRoutes.allEndpoints ++
                         DashboardProviderRoutes.allEndpoints ++
                         ComboRoutes.allEndpoints ++
                         AttendantRoutes.allEndpoints ++
                         PaymentRoutes.allEndpoints,
                         swaggerEnabled
                       )

      routes = CorrelationIdMiddleware(
                 HealthEndpoints.routes[IO](xa) <+>
                 AuthRoutes.routes[IO](providerService, authService) <+>
                 StorefrontRoutes.routes[IO](storefrontService) <+>
                 AvailabilityRoutes.routes[IO](availabilityService, storefrontService) <+>
                 StorefrontBookingRoutes.routes[IO](bookingService) <+>
                 DashboardBookingRoutes.routes[IO](bookingService, config.jwt.secret) <+>
                 DashboardProviderRoutes.routes[IO](providerService, config.jwt.secret) <+>
                 ItemRoutes.routes[IO](itemService, config.jwt.secret) <+>
                 ComboRoutes.routes[IO](comboService, config.jwt.secret) <+>
                 AttendantRoutes.routes[IO](attendantService, config.jwt.secret) <+>
                 PaymentRoutes.routes[IO](paymentService, config.jwt.secret) <+>
                 docsRoute.getOrElse(HttpRoutes.empty[IO])
               )

      logBody = sys.env.getOrElse("LOG_HTTP_BODY", "false") == "true"
      httpApp = HttpLogger.httpApp[IO](logHeaders = false, logBody = logBody)(routes.orNotFound)

      server <- EmberServerBuilder
        .default[IO]
        .withHost(Host.fromString(config.http.host).getOrElse(host"0.0.0.0"))
        .withPort(Port.fromInt(config.http.port).getOrElse(port"8080"))
        .withHttpApp(httpApp)
        .build

      _ <- Resource.eval(logger.info(s"Server started at http://${config.http.host}:${config.http.port}"))
    } yield server).useForever
  }
}
