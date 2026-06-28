package com.locarya.adapters.external

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.{AsaasCharge, BookingId}
import com.locarya.domain.ports.PaymentGateway
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.LocalDate

final class AsaasClientLive[F[_]: Async] private (
  httpClient: HttpClient,
  apiKey:     String,
  baseUrl:    String
) extends PaymentGateway[F]:

  private def asaasRequest(method: String, path: String, bodyOpt: Option[String]): HttpRequest =
    val builder = HttpRequest
      .newBuilder(URI.create(s"$baseUrl$path"))
      .header("access_token", apiKey)
      .header("Content-Type", "application/json")
    bodyOpt match
      case Some(body) => builder.method(method, BodyPublishers.ofString(body)).build()
      case None       => builder.method(method, BodyPublishers.noBody()).build()

  private def executeRequest(req: HttpRequest): F[Json] =
    Async[F].blocking(httpClient.send(req, BodyHandlers.ofString())).flatMap { resp =>
      parse(resp.body()) match
        case Right(json) => json.pure[F]
        case Left(err)   => Async[F].raiseError(new RuntimeException(s"Failed to parse Asaas response: ${err.message}"))
    }

  def createCharge(bookingId: BookingId, walletId: String, amount: BigDecimal, customerEmail: String): F[AsaasCharge] =
    val body = Json.obj(
      "billingType"       -> "PIX".asJson,
      "value"             -> amount.asJson,
      "dueDate"           -> LocalDate.now().plusDays(1).toString.asJson,
      "description"       -> s"Reserva ${bookingId.value}".asJson,
      "externalReference" -> bookingId.value.asJson,
      "split" -> Json.arr(
        Json.obj(
          "walletId"        -> walletId.asJson,
          "percentualValue" -> 100.asJson
        )
      )
    )
    val req = asaasRequest("POST", "/v3/payments", Some(body.noSpaces))
    executeRequest(req).flatMap { json =>
      val chargeId   = json.hcursor.downField("id").as[String].getOrElse("")
      val paymentUrl = json.hcursor.downField("invoiceUrl").as[String].getOrElse("")
      AsaasCharge
        .create(chargeId, paymentUrl)
        .fold(e => Async[F].raiseError(new RuntimeException(e.message)), _.pure[F])
    }

  def cancelCharge(chargeId: String): F[Unit] =
    val req = asaasRequest("DELETE", s"/v3/payments/$chargeId", None)
    executeRequest(req).void

object AsaasClientLive:
  def make[F[_]: Async]: AsaasClientLive[F] =
    val apiKey     = sys.env.getOrElse("ASAAS_API_KEY", "")
    val baseUrl    = sys.env.getOrElse("ASAAS_BASE_URL", "https://api.asaas.com")
    val httpClient = HttpClient.newHttpClient()
    new AsaasClientLive[F](httpClient, apiKey, baseUrl)
