package com.locarya.infrastructure.db

import cats.effect.IO
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import munit.CatsEffectSuite
import doobie._
import doobie.implicits._
import org.flywaydb.core.Flyway
import org.testcontainers.utility.DockerImageName

class MigrationSpec extends CatsEffectSuite with TestContainerForAll {

  // TODO: Requires Docker to be running. To enable: start Docker and remove .ignore below
  // Run with: docker-compose up -d && sbt test

  override val containerDef: PostgreSQLContainer.Def = PostgreSQLContainer.Def(
    dockerImageName = DockerImageName.parse("postgres:16-alpine")
  )

  test("V1 migration creates tables and indexes".ignore) {
    withContainers { postgres =>
      // Apply Flyway migration
      val flyway = Flyway
        .configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("filesystem:src/main/resources/db/migration")
        .load()

      flyway.migrate()

      Database.transactor[IO](postgres.jdbcUrl, postgres.username, postgres.password).use { xa =>
        for {
          // Verify sample tables exist
          providerTableExists <- sql"""
            SELECT EXISTS (
              SELECT FROM information_schema.tables
              WHERE table_schema = 'public'
              AND table_name = 'providers'
            )
          """.query[Boolean].unique.transact(xa)

          bookingsTableExists <- sql"""
            SELECT EXISTS (
              SELECT FROM information_schema.tables
              WHERE table_schema = 'public'
              AND table_name = 'bookings'
            )
          """.query[Boolean].unique.transact(xa)

          itemImagesTableExists <- sql"""
            SELECT EXISTS (
              SELECT FROM information_schema.tables
              WHERE table_schema = 'public'
              AND table_name = 'item_images'
            )
          """.query[Boolean].unique.transact(xa)

          // Verify sample indexes exist
          providerEmailIndexExists <- sql"""
            SELECT EXISTS (
              SELECT FROM pg_indexes
              WHERE tablename = 'providers'
              AND indexname = 'idx_providers_email'
            )
          """.query[Boolean].unique.transact(xa)

          bookingDatesIndexExists <- sql"""
            SELECT EXISTS (
              SELECT FROM pg_indexes
              WHERE tablename = 'bookings'
              AND indexname = 'idx_bookings_dates'
            )
          """.query[Boolean].unique.transact(xa)

        } yield {
          assert(providerTableExists, "providers table should exist")
          assert(bookingsTableExists, "bookings table should exist")
          assert(itemImagesTableExists, "item_images table should exist")
          assert(providerEmailIndexExists, "idx_providers_email index should exist")
          assert(bookingDatesIndexExists, "idx_bookings_dates index should exist")
        }
      }
    }
  }
}
