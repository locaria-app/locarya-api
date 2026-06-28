package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.TapirSupport.{ErrorBody, securedBase, validateBearer}
import com.locarya.domain.models.*
import com.locarya.domain.ports.AsaasOnboardingService
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.Http4sServerInterpreter

object DashboardAsaasRoutes:

  private case class OnboardingResponse(walletId: String)
  private given Encoder[OnboardingResponse] = deriveEncoder
  private given Decoder[OnboardingResponse] = deriveDecoder
  private given Schema[OnboardingResponse]  = Schema.derived

  private val onboardE = securedBase.post
    .in("dashboard" / "asaas" / "onboarding")
    .out(jsonBody[OnboardingResponse])

  val allEndpoints: List[AnyEndpoint] = List(onboardE)

  def routes[F[_]: Async](
    onboardingService: AsaasOnboardingService[F],
    jwtSecret:         String
  ): HttpRoutes[F] =

    type Err = (StatusCode, ErrorBody)

    def security(token: String): F[Either[Err, ProviderId]] =
      validateBearer(token, jwtSecret).pure[F]

    val onboardServer = onboardE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => _ =>
        onboardingService.onboard(providerId)
          .map(walletId => Right(OnboardingResponse(walletId)))
          .handleErrorWith { _ =>
            Left((StatusCode.InternalServerError, ErrorBody("Onboarding failed"))).pure[F]
          }
      }

    Http4sServerInterpreter[F]().toRoutes(List(onboardServer))
