package com.locarya.adapters.http

import cats.effect.IO
import com.locarya.adapters.persistence.Database
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.implicits._

class HealthEndpointsSpec extends CatsEffectSuite {

  // TODO: These tests require Docker Compose to be running: docker-compose up -d
  // For full integration testing with Testcontainers, see MigrationSpec

  val testDbUrl = "jdbc:postgresql://localhost:5432/locarya"
  val testDbUser = "locarya"
  val testDbPassword = "locarya_dev_password"

  test("GET /health/live returns 200 OK".ignore) {
    val request = Request[IO](Method.GET, uri"/health/live")

    Database.transactor[IO](testDbUrl, testDbUser, testDbPassword).use { xa =>
      HealthEndpoints.routes[IO](xa).orNotFound(request).map { response =>
        assertEquals(response.status, Status.Ok)
      }
    }
  }

  test("GET /health/ready returns 200 when database is available".ignore) {
    val request = Request[IO](Method.GET, uri"/health/ready")

    Database.transactor[IO](testDbUrl, testDbUser, testDbPassword).use { xa =>
      HealthEndpoints.routes[IO](xa).orNotFound(request).map { response =>
        assertEquals(response.status, Status.Ok)
      }
    }
  }
}
