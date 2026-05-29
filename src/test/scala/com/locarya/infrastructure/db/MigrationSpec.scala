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

  test("V2 migration applies successfully".ignore) {
    withContainers { postgres =>
      val flyway = Flyway
        .configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("filesystem:src/main/resources/db/migration")
        .load()

      flyway.migrate()

      Database.transactor[IO](postgres.jdbcUrl, postgres.username, postgres.password).use { xa =>
        sql"""
          SELECT EXISTS (
            SELECT 1 FROM flyway_schema_history
            WHERE version = '2'
            AND success = true
          )
        """.query[Boolean].unique.transact(xa).map { v2Applied =>
          assert(v2Applied, "V2 migration should be applied and successful")
        }
      }
    }
  }

  test("V2 migration adds provider columns with correct nullability".ignore) {
    withContainers { postgres =>
      val flyway = Flyway
        .configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("filesystem:src/main/resources/db/migration")
        .load()

      flyway.migrate()

      Database.transactor[IO](postgres.jdbcUrl, postgres.username, postgres.password).use { xa =>
        for {
          cpfColumnInfo <- sql"""
            SELECT is_nullable, character_maximum_length
            FROM information_schema.columns
            WHERE table_name = 'providers' AND column_name = 'cpf'
          """.query[(String, Int)].unique.transact(xa)

          cityColumnInfo <- sql"""
            SELECT is_nullable, character_maximum_length
            FROM information_schema.columns
            WHERE table_name = 'providers' AND column_name = 'city'
          """.query[(String, Int)].unique.transact(xa)

          stateColumnInfo <- sql"""
            SELECT is_nullable, character_maximum_length
            FROM information_schema.columns
            WHERE table_name = 'providers' AND column_name = 'state'
          """.query[(String, Int)].unique.transact(xa)

          cnpjColumnInfo <- sql"""
            SELECT is_nullable
            FROM information_schema.columns
            WHERE table_name = 'providers' AND column_name = 'cnpj'
          """.query[String].unique.transact(xa)

        } yield {
          assert(cpfColumnInfo._1 == "YES", "cpf should be nullable")
          assert(cpfColumnInfo._2 == 11, "cpf should be VARCHAR(11)")
          assert(cityColumnInfo._1 == "NO", "city should be NOT NULL")
          assert(cityColumnInfo._2 == 100, "city should be VARCHAR(100)")
          assert(stateColumnInfo._1 == "NO", "state should be NOT NULL")
          assert(stateColumnInfo._2 == 2, "state should be VARCHAR(2)")
          assert(cnpjColumnInfo == "YES", "cnpj should now be nullable")
        }
      }
    }
  }

  test("V2 migration enforces exactly one tax ID via CHECK constraint".ignore) {
    withContainers { postgres =>
      val flyway = Flyway
        .configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("filesystem:src/main/resources/db/migration")
        .load()

      flyway.migrate()

      Database.transactor[IO](postgres.jdbcUrl, postgres.username, postgres.password).use { xa =>
        val providerId = java.util.UUID.randomUUID().toString

        for {
          // Attempt to insert with both CPF and CNPJ - should fail
          bothFails <- sql"""
            INSERT INTO providers (id, email, cpf, cnpj, business_name, trade_name, city, state, contract_status, created_at, updated_at)
            VALUES (${java.util.UUID.randomUUID().toString}, 'both@test.com', '12345678901', '12345678901234', 'Test', 'Test', 'São Paulo', 'SP', 'ACTIVE', NOW(), NOW())
          """.update.run.transact(xa).attempt

          // Attempt to insert with neither CPF nor CNPJ - should fail
          neitherFails <- sql"""
            INSERT INTO providers (id, email, cpf, cnpj, business_name, trade_name, city, state, contract_status, created_at, updated_at)
            VALUES (${java.util.UUID.randomUUID().toString}, 'neither@test.com', NULL, NULL, 'Test', 'Test', 'São Paulo', 'SP', 'ACTIVE', NOW(), NOW())
          """.update.run.transact(xa).attempt

          // Insert with only CPF - should succeed
          cpfOnlySucceeds <- sql"""
            INSERT INTO providers (id, email, cpf, cnpj, business_name, trade_name, city, state, contract_status, created_at, updated_at)
            VALUES (${java.util.UUID.randomUUID().toString}, 'cpf@test.com', '12345678901', NULL, 'Test CPF', 'Test', 'São Paulo', 'SP', 'ACTIVE', NOW(), NOW())
          """.update.run.transact(xa).attempt

          // Insert with only CNPJ - should succeed
          cnpjOnlySucceeds <- sql"""
            INSERT INTO providers (id, email, cnpj, cpf, business_name, trade_name, city, state, contract_status, created_at, updated_at)
            VALUES (${java.util.UUID.randomUUID().toString}, 'cnpj@test.com', '12345678901234', NULL, 'Test CNPJ', 'Test', 'Rio de Janeiro', 'RJ', 'ACTIVE', NOW(), NOW())
          """.update.run.transact(xa).attempt

        } yield {
          assert(bothFails.isLeft, "Insert with both CPF and CNPJ should fail")
          assert(neitherFails.isLeft, "Insert with neither CPF nor CNPJ should fail")
          assert(cpfOnlySucceeds.isRight, "Insert with only CPF should succeed")
          assert(cnpjOnlySucceeds.isRight, "Insert with only CNPJ should succeed")
        }
      }
    }
  }

  test("V2 migration creates unique partial index on CPF".ignore) {
    withContainers { postgres =>
      val flyway = Flyway
        .configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("filesystem:src/main/resources/db/migration")
        .load()

      flyway.migrate()

      Database.transactor[IO](postgres.jdbcUrl, postgres.username, postgres.password).use { xa =>
        for {
          // Verify index exists
          indexExists <- sql"""
            SELECT EXISTS (
              SELECT FROM pg_indexes
              WHERE tablename = 'providers'
              AND indexname = 'idx_providers_cpf'
            )
          """.query[Boolean].unique.transact(xa)

          // Insert first provider with CPF - should succeed
          firstInsert <- sql"""
            INSERT INTO providers (id, email, cpf, cnpj, business_name, trade_name, city, state, contract_status, created_at, updated_at)
            VALUES (${java.util.UUID.randomUUID().toString}, 'first@test.com', '11111111111', NULL, 'First', 'First', 'São Paulo', 'SP', 'ACTIVE', NOW(), NOW())
          """.update.run.transact(xa).attempt

          // Attempt to insert second provider with same CPF - should fail
          duplicateFails <- sql"""
            INSERT INTO providers (id, email, cpf, cnpj, business_name, trade_name, city, state, contract_status, created_at, updated_at)
            VALUES (${java.util.UUID.randomUUID().toString}, 'duplicate@test.com', '11111111111', NULL, 'Duplicate', 'Duplicate', 'Rio', 'RJ', 'ACTIVE', NOW(), NOW())
          """.update.run.transact(xa).attempt

          // Insert two providers with NULL CPF - should succeed (partial index allows multiple NULLs)
          firstNull <- sql"""
            INSERT INTO providers (id, email, cpf, cnpj, business_name, trade_name, city, state, contract_status, created_at, updated_at)
            VALUES (${java.util.UUID.randomUUID().toString}, 'null1@test.com', NULL, '11111111111111', 'Null1', 'Null1', 'Curitiba', 'PR', 'ACTIVE', NOW(), NOW())
          """.update.run.transact(xa).attempt

          secondNull <- sql"""
            INSERT INTO providers (id, email, cpf, cnpj, business_name, trade_name, city, state, contract_status, created_at, updated_at)
            VALUES (${java.util.UUID.randomUUID().toString}, 'null2@test.com', NULL, '22222222222222', 'Null2', 'Null2', 'Porto Alegre', 'RS', 'ACTIVE', NOW(), NOW())
          """.update.run.transact(xa).attempt

        } yield {
          assert(indexExists, "idx_providers_cpf index should exist")
          assert(firstInsert.isRight, "First provider with CPF should insert successfully")
          assert(duplicateFails.isLeft, "Duplicate CPF should fail")
          assert(firstNull.isRight, "First provider with NULL CPF should succeed")
          assert(secondNull.isRight, "Second provider with NULL CPF should succeed (partial index allows multiple NULLs)")
        }
      }
    }
  }

  test("V2 migration adds booking delivery address columns".ignore) {
    withContainers { postgres =>
      val flyway = Flyway
        .configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("filesystem:src/main/resources/db/migration")
        .load()

      flyway.migrate()

      Database.transactor[IO](postgres.jdbcUrl, postgres.username, postgres.password).use { xa =>
        for {
          deliveryStreet <- sql"""
            SELECT is_nullable, character_maximum_length
            FROM information_schema.columns
            WHERE table_name = 'bookings' AND column_name = 'delivery_street'
          """.query[(String, Int)].unique.transact(xa)

          deliveryNumber <- sql"""
            SELECT is_nullable, character_maximum_length
            FROM information_schema.columns
            WHERE table_name = 'bookings' AND column_name = 'delivery_number'
          """.query[(String, Int)].unique.transact(xa)

          deliveryNeighborhood <- sql"""
            SELECT is_nullable, character_maximum_length
            FROM information_schema.columns
            WHERE table_name = 'bookings' AND column_name = 'delivery_neighborhood'
          """.query[(String, Int)].unique.transact(xa)

          deliveryCity <- sql"""
            SELECT is_nullable, character_maximum_length
            FROM information_schema.columns
            WHERE table_name = 'bookings' AND column_name = 'delivery_city'
          """.query[(String, Int)].unique.transact(xa)

          deliveryState <- sql"""
            SELECT is_nullable, character_maximum_length
            FROM information_schema.columns
            WHERE table_name = 'bookings' AND column_name = 'delivery_state'
          """.query[(String, Int)].unique.transact(xa)

          deliveryCep <- sql"""
            SELECT is_nullable, character_maximum_length
            FROM information_schema.columns
            WHERE table_name = 'bookings' AND column_name = 'delivery_cep'
          """.query[(String, Int)].unique.transact(xa)

          deliveryComplement <- sql"""
            SELECT is_nullable, character_maximum_length
            FROM information_schema.columns
            WHERE table_name = 'bookings' AND column_name = 'delivery_complement'
          """.query[(String, Int)].unique.transact(xa)

        } yield {
          assert(deliveryStreet._1 == "YES", "delivery_street should be nullable")
          assert(deliveryStreet._2 == 255, "delivery_street should be VARCHAR(255)")
          assert(deliveryNumber._1 == "YES", "delivery_number should be nullable")
          assert(deliveryNumber._2 == 20, "delivery_number should be VARCHAR(20)")
          assert(deliveryNeighborhood._1 == "YES", "delivery_neighborhood should be nullable")
          assert(deliveryNeighborhood._2 == 100, "delivery_neighborhood should be VARCHAR(100)")
          assert(deliveryCity._1 == "YES", "delivery_city should be nullable")
          assert(deliveryCity._2 == 100, "delivery_city should be VARCHAR(100)")
          assert(deliveryState._1 == "YES", "delivery_state should be nullable")
          assert(deliveryState._2 == 2, "delivery_state should be VARCHAR(2)")
          assert(deliveryCep._1 == "YES", "delivery_cep should be nullable")
          assert(deliveryCep._2 == 8, "delivery_cep should be VARCHAR(8)")
          assert(deliveryComplement._1 == "YES", "delivery_complement should be nullable")
          assert(deliveryComplement._2 == 255, "delivery_complement should be VARCHAR(255)")
        }
      }
    }
  }

  test("V2 migration allows bookings with delivery address".ignore) {
    withContainers { postgres =>
      val flyway = Flyway
        .configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("filesystem:src/main/resources/db/migration")
        .load()

      flyway.migrate()

      Database.transactor[IO](postgres.jdbcUrl, postgres.username, postgres.password).use { xa =>
        val providerId = java.util.UUID.randomUUID().toString
        val customerId = java.util.UUID.randomUUID().toString

        for {
          // Create a provider (with CPF)
          _ <- sql"""
            INSERT INTO providers (id, email, cpf, cnpj, business_name, trade_name, city, state, contract_status, created_at, updated_at)
            VALUES ($providerId, 'provider@test.com', '12345678901', NULL, 'Test Provider', 'Test', 'São Paulo', 'SP', 'ACTIVE', NOW(), NOW())
          """.update.run.transact(xa)

          // Create a customer
          _ <- sql"""
            INSERT INTO customers (id, email, cpf, name, created_at, updated_at)
            VALUES ($customerId, 'customer@test.com', '98765432100', 'Test Customer', NOW(), NOW())
          """.update.run.transact(xa)

          // Insert booking with complete delivery address - should succeed
          bookingWithAddress <- sql"""
            INSERT INTO bookings (id, provider_id, customer_id, start_date, end_date, total_amount, status, created_at, updated_at,
                                  delivery_street, delivery_number, delivery_neighborhood, delivery_city, delivery_state, delivery_cep, delivery_complement)
            VALUES (${java.util.UUID.randomUUID().toString}, $providerId, $customerId, '2026-06-01', '2026-06-03', 100.00, 'PENDING', NOW(), NOW(),
                    'Rua das Flores', '123', 'Centro', 'São Paulo', 'SP', '01234567', 'Apto 45')
          """.update.run.transact(xa).attempt

          // Insert booking with NULL delivery_complement - should succeed
          bookingNoComplement <- sql"""
            INSERT INTO bookings (id, provider_id, customer_id, start_date, end_date, total_amount, status, created_at, updated_at,
                                  delivery_street, delivery_number, delivery_neighborhood, delivery_city, delivery_state, delivery_cep, delivery_complement)
            VALUES (${java.util.UUID.randomUUID().toString}, $providerId, $customerId, '2026-07-01', '2026-07-03', 150.00, 'PENDING', NOW(), NOW(),
                    'Av Paulista', '1000', 'Bela Vista', 'São Paulo', 'SP', '01310100', NULL)
          """.update.run.transact(xa).attempt

          // Insert booking with all delivery fields NULL - should succeed
          bookingNoAddress <- sql"""
            INSERT INTO bookings (id, provider_id, customer_id, start_date, end_date, total_amount, status, created_at, updated_at,
                                  delivery_street, delivery_number, delivery_neighborhood, delivery_city, delivery_state, delivery_cep, delivery_complement)
            VALUES (${java.util.UUID.randomUUID().toString}, $providerId, $customerId, '2026-08-01', '2026-08-03', 200.00, 'PENDING', NOW(), NOW(),
                    NULL, NULL, NULL, NULL, NULL, NULL, NULL)
          """.update.run.transact(xa).attempt

        } yield {
          assert(bookingWithAddress.isRight, "Booking with complete delivery address should succeed")
          assert(bookingNoComplement.isRight, "Booking without complement should succeed")
          assert(bookingNoAddress.isRight, "Booking without delivery address should succeed")
        }
      }
    }
  }
}
