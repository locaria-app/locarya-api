package com.locarya.adapters.http

import munit.FunSuite
import sttp.tapir.AnyEndpoint
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.apispec.openapi.circe.yaml._

class OpenApiSpecSpec extends FunSuite:

  private val allEndpoints: List[AnyEndpoint] =
    ItemRoutes.allEndpoints ++
      AuthRoutes.allEndpoints ++
      StorefrontRoutes.allEndpoints ++
      AvailabilityRoutes.allEndpoints ++
      StorefrontBookingRoutes.allEndpoints ++
      DashboardBookingRoutes.allEndpoints ++
      ComboRoutes.allEndpoints ++
      AttendantRoutes.allEndpoints ++
      PaymentRoutes.allEndpoints ++
      DashboardProviderRoutes.allEndpoints ++
      DashboardAsaasRoutes.allEndpoints

  test("generated OpenAPI YAML is non-empty and starts with 'openapi:'") {
    val yaml = OpenAPIDocsInterpreter().toOpenAPI(allEndpoints, "Locarya API", "1.0").toYaml
    assert(yaml.nonEmpty, "Generated YAML must not be empty")
    assert(yaml.startsWith("openapi:"), s"Expected YAML to start with 'openapi:' but got: ${yaml.take(200)}")
  }
