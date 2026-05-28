package com.locarya.http.middleware

import cats.data.OptionT
import cats.effect.{IO, IOLocal}
import cats.syntax.all._
import org.http4s._
import org.typelevel.ci._

import java.util.UUID

/**
 * Deep module for correlation ID propagation.
 * Hides IOLocal complexity behind simple apply interface.
 *
 * Propagates correlation IDs via IOLocal (survives fiber shifts in Cats Effect).
 * MDC integration for SLF4J compatibility can be added later.
 */
object CorrelationIdMiddleware {

  private val correlationIdHeader = ci"X-Correlation-ID"

  /**
   * Wraps HttpRoutes to ensure every request has a correlation ID.
   * Generates one if missing, preserves if present.
   */
  def apply(routes: HttpRoutes[IO]): HttpRoutes[IO] = {
    HttpRoutes[IO] { req =>
      val correlationId = req.headers
        .get(correlationIdHeader)
        .map(_.head.value)
        .getOrElse(UUID.randomUUID().toString)

      // Store in IOLocal for downstream code to access
      OptionT.liftF(IOLocal(correlationId)).flatMap { local =>
        for {
          _ <- OptionT.liftF(local.set(correlationId))
          response <- routes(req)
          responseWithHeader = response.putHeaders(Header.Raw(correlationIdHeader, correlationId))
        } yield responseWithHeader
      }
    }
  }
}

