package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.middleware.AuthMiddleware
import com.locarya.domain.models.*
import com.locarya.domain.ports.{ComboService, CreateComboRequest, UpdateComboRequest}
import io.circe.generic.auto.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl

object ComboRoutes:

  private case class ComboItemCompositionBody(itemId: String, quantity: Int)

  private case class CreateComboBody(
    name:             String,
    description:      String,
    dailyRate:        BigDecimal,
    itemCompositions: List[ComboItemCompositionBody]
  )

  private case class UpdateComboBody(
    name:             String,
    description:      String,
    dailyRate:        BigDecimal,
    itemCompositions: Option[List[ComboItemCompositionBody]]
  )

  private case class ComboItemCompositionResponse(itemId: String, quantity: Int)

  private case class ComboResponseBody(
    comboId:          String,
    providerId:       String,
    name:             String,
    description:      String,
    dailyRate:        BigDecimal,
    isActive:         Boolean,
    itemCompositions: List[ComboItemCompositionResponse]
  )

  private case class CreateComboResponseBody(comboId: String)
  private case class ErrorResponseBody(error: String)

  private def toResponseBody(combo: Combo): ComboResponseBody =
    ComboResponseBody(
      comboId          = combo.id.value,
      providerId       = combo.providerId.value,
      name             = combo.name,
      description      = combo.description,
      dailyRate        = combo.dailyRate.amount,
      isActive         = combo.isActive,
      itemCompositions = combo.items.map(c => ComboItemCompositionResponse(c.itemId.value, c.quantity))
    )

  def routes[F[_]: Async](
    comboService: ComboService[F],
    jwtSecret:    String
  ): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*

    given EntityDecoder[F, CreateComboBody] = jsonOf[F, CreateComboBody]
    given EntityDecoder[F, UpdateComboBody] = jsonOf[F, UpdateComboBody]

    AuthMiddleware.withProviderId[F](jwtSecret) { rawProviderId =>

      val providerIdF: F[ProviderId] =
        ProviderId.fromString(rawProviderId)
          .fold(err => ComboError.InvalidInput(err).raiseError[F, ProviderId], _.pure[F])

      HttpRoutes.of[F]:

        case req @ POST -> Root / "dashboard" / "combos" =>
          req.as[CreateComboBody].flatMap { body =>
            for
              pid           <- providerIdF
              dailyRate     <- Money.fromAmount(body.dailyRate)
                                 .fold(err => ComboError.InvalidInput(err).raiseError[F, Money], _.pure[F])
              compositions  <- body.itemCompositions.traverse { c =>
                                 ItemId.fromString(c.itemId)
                                   .fold(err => ComboError.InvalidInput(err).raiseError[F, ComboItemDefinition],
                                         id => ComboItemDefinition(id, c.quantity).pure[F])
                               }
              request        = CreateComboRequest(
                                 providerId       = pid,
                                 name             = body.name,
                                 description      = body.description,
                                 dailyRate        = dailyRate,
                                 itemCompositions = compositions
                               )
              comboId       <- comboService.createCombo(request)
              resp          <- Created(CreateComboResponseBody(comboId.value).asJson)
            yield resp
          }.handleErrorWith {
            case e: ComboError =>
              BadRequest(ErrorResponseBody(e.getMessage).asJson)
            case _: MalformedMessageBodyFailure =>
              BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
            case _: InvalidMessageBodyFailure =>
              BadRequest(ErrorResponseBody("Invalid request body").asJson)
          }

        case GET -> Root / "dashboard" / "combos" / comboIdStr =>
          (for
            pid     <- providerIdF
            comboId <- ComboId.fromString(comboIdStr)
                         .fold(err => ComboError.InvalidInput(err).raiseError[F, ComboId], _.pure[F])
            combo   <- comboService.getCombo(comboId, pid)
            resp    <- Ok(toResponseBody(combo).asJson)
          yield resp)
            .handleErrorWith {
              case _: ComboError.NotFound    => NotFound(ErrorResponseBody("Combo not found").asJson)
              case _: ComboError.Forbidden   => Forbidden(ErrorResponseBody("Access denied").asJson)
              case e: ComboError             => BadRequest(ErrorResponseBody(e.getMessage).asJson)
            }

        case req @ PUT -> Root / "dashboard" / "combos" / comboIdStr =>
          req.as[UpdateComboBody].flatMap { body =>
            for
              pid          <- providerIdF
              comboId      <- ComboId.fromString(comboIdStr)
                                .fold(err => ComboError.InvalidInput(err).raiseError[F, ComboId], _.pure[F])
              dailyRate    <- Money.fromAmount(body.dailyRate)
                                .fold(err => ComboError.InvalidInput(err).raiseError[F, Money], _.pure[F])
              compositions <- body.itemCompositions.traverse { list =>
                                list.traverse { c =>
                                  ItemId.fromString(c.itemId)
                                    .fold(err => ComboError.InvalidInput(err).raiseError[F, ComboItemDefinition],
                                          id => ComboItemDefinition(id, c.quantity).pure[F])
                                }
                              }
              request       = UpdateComboRequest(
                                comboId          = comboId,
                                providerId       = pid,
                                name             = body.name,
                                description      = body.description,
                                dailyRate        = dailyRate,
                                itemCompositions = compositions
                              )
              _            <- comboService.updateCombo(request)
              resp         <- Ok(().asJson)
            yield resp
          }.handleErrorWith {
            case _: ComboError.HasBookings  => Conflict(ErrorResponseBody("Combo has existing bookings — composition cannot be changed").asJson)
            case _: ComboError.NotFound     => NotFound(ErrorResponseBody("Combo not found").asJson)
            case _: ComboError.Forbidden    => Forbidden(ErrorResponseBody("Access denied").asJson)
            case e: ComboError              => BadRequest(ErrorResponseBody(e.getMessage).asJson)
            case _: MalformedMessageBodyFailure =>
              BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
            case _: InvalidMessageBodyFailure =>
              BadRequest(ErrorResponseBody("Invalid request body").asJson)
          }

        case DELETE -> Root / "dashboard" / "combos" / comboIdStr =>
          (for
            pid     <- providerIdF
            comboId <- ComboId.fromString(comboIdStr)
                         .fold(err => ComboError.InvalidInput(err).raiseError[F, ComboId], _.pure[F])
            _       <- comboService.softDeleteCombo(comboId, pid)
            resp    <- Ok(().asJson)
          yield resp)
            .handleErrorWith {
              case _: ComboError.NotFound    => NotFound(ErrorResponseBody("Combo not found").asJson)
              case _: ComboError.Forbidden   => Forbidden(ErrorResponseBody("Access denied").asJson)
              case e: ComboError             => BadRequest(ErrorResponseBody(e.getMessage).asJson)
            }
    }
