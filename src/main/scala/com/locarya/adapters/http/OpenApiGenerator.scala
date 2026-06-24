package com.locarya.adapters.http

import sttp.tapir.AnyEndpoint
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.apispec.openapi.circe.yaml._
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

object OpenApiGenerator:

  def main(args: Array[String]): Unit =
    val allEndpoints: List[AnyEndpoint] =
      ItemRoutes.allEndpoints ++
        AuthRoutes.allEndpoints ++
        StorefrontRoutes.allEndpoints ++
        AvailabilityRoutes.allEndpoints ++
        StorefrontBookingRoutes.allEndpoints ++
        DashboardBookingRoutes.allEndpoints ++
        ComboRoutes.allEndpoints ++
        AttendantRoutes.allEndpoints ++
        PaymentRoutes.allEndpoints

    val outputPath = args.headOption.getOrElse("openapi.yaml")
    val yaml       = OpenAPIDocsInterpreter().toOpenAPI(allEndpoints, "Locarya API", "1.0").toYaml
    Files.write(Paths.get(outputPath), yaml.getBytes(StandardCharsets.UTF_8))
    println(s"OpenAPI spec written to $outputPath")
