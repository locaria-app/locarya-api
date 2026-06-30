package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.TapirSupport.{ErrorBody, publicBase}
import com.locarya.domain.ports.AsaasWebhookService
import io.circe.generic.auto.*
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.Http4sServerInterpreter

object AsaasWebhookRoutes:

  private case class AsaasPaymentEvent(id: String, value: BigDecimal)
  private case class AsaasWebhookBody(event: String, payment: AsaasPaymentEvent)

  private val webhookE = publicBase.post
    .in("webhooks" / "asaas")
    .in(header[Option[String]]("asaas-access-token"))
    .in(jsonBody[AsaasWebhookBody])
    .out(emptyOutput)
    .errorOut(statusCode.and(jsonBody[ErrorBody]))

  val allEndpoints: List[AnyEndpoint] = List(webhookE)

  def routes[F[_]: Async](
    webhookSvc:   AsaasWebhookService[F],
    webhookToken: String
  ): HttpRoutes[F] =

    val server = webhookE.serverLogic[F] { case (tokenOpt, body) =>
      if !tokenOpt.contains(webhookToken) then
        Left((StatusCode.Unauthorized, ErrorBody("Invalid or missing webhook token"))).pure[F]
      else
        body.event match
          case "PAYMENT_CONFIRMED" =>
            webhookSvc.handlePaymentConfirmed(body.payment.id, body.payment.value).as(Right(()))
          case _ =>
            Right(()).pure[F]
    }

    Http4sServerInterpreter[F]().toRoutes(List(server))
