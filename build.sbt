// 3.3.7 LTS — 3.3.4+ is required for scoverage's `coverageExcludedPackages`
// (the compiler's -coverage-exclude-* flags were backported to the 3.3 LTS line).
val scala3Version = "3.3.7"

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

      // Password hashing
      "org.mindrot" % "jbcrypt" % "0.4",

      // JWT
      "com.github.jwt-scala" %% "jwt-circe" % "9.4.6",

      // Tapir (API documentation + server interpreter)
      "com.softwaremill.sttp.tapir" %% "tapir-core"              % "1.11.50",
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server"     % "1.11.50",
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % "1.11.50",
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe"        % "1.11.50",

      // AWS SDK v2 (S3 presigned URLs — Cloudflare R2 compatible)
      "software.amazon.awssdk" % "s3" % "2.26.31",

      // Test — in-memory port impls + stub gateways (no Testcontainers). See ADR 0007.
      "org.scalameta" %% "munit" % "1.1.0" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.1.0" % Test
    ),

    // Coverage: exclude driven/driving adapters, the composition root and config —
    // covered by the deferred integration suite, not unit-covered. See ADR 0007.
    coverageExcludedPackages := "com\\.locarya\\.adapters\\..*;com\\.locarya\\.Main;com\\.locarya\\.config\\..*",

    // Flyway configuration
    flywayUrl := "jdbc:postgresql://localhost:5432/locarya",
    flywayUser := "locarya",
    flywayPassword := "locarya_dev_password",
    flywayLocations := Seq("filesystem:src/main/resources/db/migration"),

    // Fork a separate JVM for `sbt run` so IOApp gets the real main thread and
    // Cats Effect can shut down cleanly (avoids the non-main-thread warning).
    Compile / run / fork := true,

    // Assembly (fat JAR for Docker)
    Compile / mainClass := Some("com.locarya.Main"),
    assembly / mainClass := Some("com.locarya.Main"),
    assembly / assemblyJarName := "locarya-api.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF")  => MergeStrategy.discard
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*)             => MergeStrategy.discard
      case "module-info.class"                  => MergeStrategy.discard
      case _                                    => MergeStrategy.first
    }
  )
