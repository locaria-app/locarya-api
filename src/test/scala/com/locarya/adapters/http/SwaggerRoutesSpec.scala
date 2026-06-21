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

  test("GET /docs/index.html with AuthRoutes.allEndpoints returns 200") {
    val route = SwaggerRoutes.maybeDocsRoute[IO](AuthRoutes.allEndpoints, swaggerEnabled = true)
      .getOrElse(fail("Expected Some routes when swagger is enabled"))
    val request = Request[IO](Method.GET, uri"/docs/index.html")
    route.orNotFound(request).map { resp =>
      assertEquals(resp.status, Status.Ok)
    }
  }

  test("AuthRoutes.allEndpoints exposes auth/signup and auth/login paths") {
    IO {
      val shows = AuthRoutes.allEndpoints.map(_.show)
      assert(shows.exists(s => s.contains("auth") && s.contains("signup")), s"auth/signup not found in: $shows")
      assert(shows.exists(s => s.contains("auth") && s.contains("login")), s"auth/login not found in: $shows")
    }
  }

  test("AuthRoutes.allEndpoints have no bearer security requirement") {
    IO {
      val shows = AuthRoutes.allEndpoints.map(_.show)
      assert(!shows.exists(_.contains("bearer")), s"Auth endpoints must not require bearer: $shows")
    }
  }
