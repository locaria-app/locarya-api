package com.locarya.adapters.external

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.NotificationService
import io.circe.Json
import io.circe.syntax.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

final class EmailNotificationAdapter[F[_]: Async] private (
  httpClient: HttpClient,
  apiKey:     String,
  fromEmail:  String
) extends NotificationService[F]:

  def notify(payload: NotificationPayload): F[Unit] = payload match
    case NotificationPayload.PaymentConfirmed(booking, customer, provider, amount) =>
      sendEmail(
        to      = customer.email.value,
        subject = s"Pagamento confirmado — Reserva ${booking.bookingCode.value}",
        html    = paymentConfirmedCustomerHtml(booking, customer, amount)
      ) >>
        sendEmail(
          to      = provider.email.value,
          subject = s"Reserva paga — ${booking.bookingCode.value}",
          html    = paymentConfirmedProviderHtml(booking, customer, provider, amount)
        )

    case NotificationPayload.BookingCreatedWithPaymentLink(booking, customer, provider, paymentUrl) =>
      sendEmail(
        to      = customer.email.value,
        subject = s"Reserva confirmada — ${booking.bookingCode.value}",
        html    = bookingCreatedCustomerHtml(booking, customer, paymentUrl)
      ) >>
        sendEmail(
          to      = provider.email.value,
          subject = s"Nova reserva com pagamento pendente — ${booking.bookingCode.value}",
          html    = bookingCreatedProviderHtml(booking, customer, provider)
        )

    case NotificationPayload.BookingStatusChanged(booking, customer, _, _, newStatus) =>
      sendEmail(
        to      = customer.email.value,
        subject = bookingStatusChangedSubject(booking, newStatus),
        html    = bookingStatusChangedHtml(booking, customer, newStatus)
      )

  private def sendEmail(to: String, subject: String, html: String): F[Unit] =
    val body = Json.obj(
      "from"    -> fromEmail.asJson,
      "to"      -> Json.arr(to.asJson),
      "subject" -> subject.asJson,
      "html"    -> html.asJson
    ).noSpaces
    val req = HttpRequest
      .newBuilder(URI.create("https://api.resend.com/emails"))
      .header("Authorization", s"Bearer $apiKey")
      .header("Content-Type", "application/json")
      .POST(BodyPublishers.ofString(body))
      .build()
    Async[F]
      .blocking(httpClient.send(req, BodyHandlers.ofString()))
      .flatMap { resp =>
        if resp.statusCode() >= 200 && resp.statusCode() < 300 then ().pure[F]
        else Async[F].raiseError(new RuntimeException(s"Resend error ${resp.statusCode()}: ${resp.body()}"))
      }

  private def paymentConfirmedCustomerHtml(booking: Booking, customer: Customer, amount: Money): String =
    s"""<p>Olá, ${customer.name}!</p>
       |<p>Seu pagamento de R$$ ${amount.amount} foi confirmado para a reserva <strong>${booking.bookingCode.value}</strong> no dia ${booking.startDate}.</p>
       |<p>Obrigado por usar a Locarya!</p>""".stripMargin

  private def paymentConfirmedProviderHtml(booking: Booking, customer: Customer, provider: Provider, amount: Money): String =
    s"""<p>Olá, ${provider.tradeName}!</p>
       |<p>A reserva <strong>${booking.bookingCode.value}</strong> do cliente ${customer.name} foi paga.</p>
       |<p>Valor: R$$ ${amount.amount} — Data: ${booking.startDate}</p>""".stripMargin

  private def bookingCreatedCustomerHtml(booking: Booking, customer: Customer, paymentUrl: String): String =
    s"""<p>Olá, ${customer.name}!</p>
       |<p>Sua reserva <strong>${booking.bookingCode.value}</strong> para ${booking.startDate} foi criada com sucesso.</p>
       |<p>Para confirmar, efetue o pagamento via Pix: <a href="$paymentUrl">$paymentUrl</a></p>""".stripMargin

  private def bookingCreatedProviderHtml(booking: Booking, customer: Customer, provider: Provider): String =
    s"""<p>Olá, ${provider.tradeName}!</p>
       |<p>Nova reserva recebida de ${customer.name} para ${booking.startDate}.</p>
       |<p>Código: <strong>${booking.bookingCode.value}</strong> — Aguardando pagamento do cliente.</p>""".stripMargin

  private def bookingStatusChangedSubject(booking: Booking, newStatus: BookingStatus): String =
    newStatus match
      case BookingStatus.Confirmed => s"Reserva confirmada — ${booking.bookingCode.value}"
      case BookingStatus.Cancelled => s"Reserva cancelada — ${booking.bookingCode.value}"
      case _                       => s"Atualização na reserva ${booking.bookingCode.value}"

  private def bookingStatusChangedHtml(booking: Booking, customer: Customer, newStatus: BookingStatus): String =
    newStatus match
      case BookingStatus.Confirmed =>
        s"""<p>Olá, ${customer.name}!</p>
           |<p>Sua reserva <strong>${booking.bookingCode.value}</strong> para ${booking.startDate} foi <strong>confirmada</strong>.</p>
           |<p>Obrigado por usar a Locarya!</p>""".stripMargin
      case BookingStatus.Cancelled =>
        s"""<p>Olá, ${customer.name}!</p>
           |<p>Sua reserva <strong>${booking.bookingCode.value}</strong> para ${booking.startDate} foi <strong>cancelada</strong>.</p>
           |<p>Em caso de dúvidas, entre em contato com o locador.</p>""".stripMargin
      case _ =>
        s"""<p>Olá, ${customer.name}!</p>
           |<p>O status da sua reserva <strong>${booking.bookingCode.value}</strong> foi atualizado.</p>""".stripMargin

object EmailNotificationAdapter:
  def make[F[_]: Async]: EmailNotificationAdapter[F] =
    val apiKey    = sys.env.getOrElse("RESEND_API_KEY", "")
    val fromEmail = sys.env.getOrElse("RESEND_FROM_EMAIL", "noreply@locarya.com.br")
    new EmailNotificationAdapter[F](HttpClient.newHttpClient(), apiKey, fromEmail)
