val scala3Version = "3.3.1"

enablePlugins(FlywayPlugin)

lazy val root = project
  .in(file("."))
  .settings(
    name := "locarya-api",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,

    libraryDependencies ++= Seq(
      // Cats Effect
      "org.typelevel" %% "cats-effect" % "3.5.4",

      // http4s
      "org.http4s" %% "http4s-ember-server" % "0.23.27",
      "org.http4s" %% "http4s-dsl" % "0.23.27",
      "org.http4s" %% "http4s-circe" % "0.23.27",

      // Doobie (database)
      "org.tpolecat" %% "doobie-core" % "1.0.0-RC5",
      "org.tpolecat" %% "doobie-postgres" % "1.0.0-RC5",
      "org.tpolecat" %% "doobie-hikari" % "1.0.0-RC5",

      // Circe (JSON)
      "io.circe" %% "circe-core" % "0.14.7",
      "io.circe" %% "circe-generic" % "0.14.7",
      "io.circe" %% "circe-parser" % "0.14.7",

      // Logging
      "org.typelevel" %% "log4cats-core" % "2.7.0",
      "org.typelevel" %% "log4cats-slf4j" % "2.7.0",
      "ch.qos.logback" % "logback-classic" % "1.5.6",
      "net.logstash.logback" % "logstash-logback-encoder" % "7.4",

      // Refined types
      "eu.timepit" %% "refined" % "0.11.2",

      // Config
      "com.typesafe" % "config" % "1.4.3",

      // Flyway (migrations)
      "org.flywaydb" % "flyway-core" % "10.15.0",
      "org.flywaydb" % "flyway-database-postgresql" % "10.15.0",

      // PostgreSQL driver
      "org.postgresql" % "postgresql" % "42.7.3",

      // Test
      "org.scalameta" %% "munit" % "0.7.29" % Test,
      "org.typelevel" %% "munit-cats-effect-3" % "1.0.7" % Test,
      "org.tpolecat" %% "doobie-munit" % "1.0.0-RC5" % Test,
      "com.dimafeng" %% "testcontainers-scala-munit" % "0.41.0" % Test,
      "com.dimafeng" %% "testcontainers-scala-postgresql" % "0.41.0" % Test
    ),

    // Test configuration
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,

    // Flyway configuration
    flywayUrl := "jdbc:postgresql://localhost:5432/locarya",
    flywayUser := "locarya",
    flywayPassword := "locarya_dev_password",
    flywayLocations := Seq("filesystem:src/main/resources/db/migration")
  )
