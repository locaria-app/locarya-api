package com.locarya.adapters.http.middleware

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.typelevel.ci.CIStringSyntax

class CorrelationIdMiddlewareSpec extends CatsEffectSuite {

  val testRoutes = HttpRoutes.of[IO] {
    case GET -> Root / "test" => Ok("test response")
  }

  test("generates X-Correlation-ID when missing") {
    val request = Request[IO](Method.GET, uri"/test")

    CorrelationIdMiddleware(testRoutes).orNotFound(request).map { response =>
      val correlationId = response.headers.get(ci"X-Correlation-ID")
      assert(correlationId.isDefined, "X-Correlation-ID header should be present")
      assert(correlationId.get.head.value.nonEmpty, "X-Correlation-ID should not be empty")
    }
  }

  test("preserves existing X-Correlation-ID") {
    val existingId = "existing-correlation-id-12345"
    val request = Request[IO](Method.GET, uri"/test")
      .putHeaders(Header.Raw(ci"X-Correlation-ID", existingId))

    CorrelationIdMiddleware(testRoutes).orNotFound(request).map { response =>
      val correlationId = response.headers.get(ci"X-Correlation-ID")
      assertEquals(correlationId.map(_.head.value), Some(existingId))
    }
  }
}
