package com.locarya.config

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

case class JwtConfig(secret: String)

case class AsaasConfig(webhookToken: String)

case class R2Config(
  endpoint:        String,
  bucketName:      String,
  accessKeyId:     String,
  secretAccessKey: String,
  publicBaseUrl:   String
)

case class AppConfig(
  database: DatabaseConfig,
  http:     HttpConfig,
  jwt:      JwtConfig,
  asaas:    AsaasConfig,
  r2:       R2Config
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
        ),
        jwt = JwtConfig(
          secret = config.getString("locarya.jwt.secret")
        ),
        asaas = AsaasConfig(
          webhookToken = sys.env.getOrElse("ASAAS_WEBHOOK_TOKEN", "")
        ),
        r2 = R2Config(
          endpoint        = config.getString("locarya.r2.endpoint"),
          bucketName      = config.getString("locarya.r2.bucket-name"),
          accessKeyId     = config.getString("locarya.r2.access-key-id"),
          secretAccessKey = config.getString("locarya.r2.secret-access-key"),
          publicBaseUrl   = config.getString("locarya.r2.public-base-url")
        )
      )
    })
  }
}
