package com.locarya.adapters.http

import cats.effect.Async
import org.http4s.HttpRoutes
import sttp.tapir.AnyEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter

object SwaggerRoutes:

  def maybeDocsRoute[F[_]: Async](
    endpoints:      List[AnyEndpoint],
    swaggerEnabled: Boolean
  ): Option[HttpRoutes[F]] =
    if swaggerEnabled then
      val docsEndpoints = SwaggerInterpreter()
        .fromEndpoints[F](endpoints, "Locarya API", "1.0")
      Some(Http4sServerInterpreter[F]().toRoutes(docsEndpoints))
    else
      None
