package com.locarya.infrastructure.config

import cats.effect._
import com.typesafe.config.ConfigFactory

import scala.util.Try

case class DatabaseConfig(
  url: String,
  user: String,
  password: String,
  poolSize: Int
)

case class HttpConfig(
  host: String,
  port: Int
)

case class AppConfig(
  database: DatabaseConfig,
  http: HttpConfig
)

object AppConfig {

  def load[F[_]: Sync]: Resource[F, AppConfig] = {
    Resource.eval(Sync[F].delay {
      val config = ConfigFactory.load()

      AppConfig(
        database = DatabaseConfig(
          url = config.getString("locarya.database.url"),
          user = config.getString("locarya.database.user"),
          password = config.getString("locarya.database.password"),
          poolSize = config.getInt("locarya.database.pool-size")
        ),
        http = HttpConfig(
          host = config.getString("locarya.http.host"),
          port = config.getInt("locarya.http.port")
        )
      )
    })
  }
}
