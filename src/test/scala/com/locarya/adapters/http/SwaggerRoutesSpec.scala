package com.locarya.adapters.http

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*

class SwaggerRoutesSpec extends CatsEffectSuite:

  test("GET /docs/index.html returns 200 when swaggerEnabled = true") {
    val route = SwaggerRoutes.maybeDocsRoute[IO](List.empty, swaggerEnabled = true)
      .getOrElse(fail("Expected Some routes when swagger is enabled"))
    val request = Request[IO](Method.GET, uri"/docs/index.html")
    route.orNotFound(request).map { resp =>
      assertEquals(resp.status, Status.Ok)
    }
  }

  test("maybeDocsRoute returns None when swaggerEnabled = false") {
    val result = SwaggerRoutes.maybeDocsRoute[IO](List.empty, swaggerEnabled = false)
    IO(assert(result.isEmpty, "Expected None when swagger is disabled"))
  }
