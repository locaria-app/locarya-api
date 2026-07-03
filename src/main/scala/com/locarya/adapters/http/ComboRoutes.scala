package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.TapirSupport.{ErrorBody, securedBase, validateBearer}
import com.locarya.domain.models.*
import com.locarya.domain.ports.{ComboService, CreateComboRequest, UpdateComboRequest}
import io.circe.generic.auto.*
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.AnyEndpoint
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.Http4sServerInterpreter

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

  private val createComboE = securedBase.post
    .in("dashboard" / "combos")
    .in(jsonBody[CreateComboBody])
    .out(statusCode(StatusCode.Created).and(jsonBody[CreateComboResponseBody]))

  private val getComboE = securedBase.get
    .in("dashboard" / "combos" / path[String]("comboId"))
    .out(jsonBody[ComboResponseBody])

  private val updateComboE = securedBase.put
    .in("dashboard" / "combos" / path[String]("comboId"))
    .in(jsonBody[UpdateComboBody])

  private val deleteComboE = securedBase.delete
    .in("dashboard" / "combos" / path[String]("comboId"))

  private val listCombosE = securedBase.get
    .in("dashboard" / "combos")
    .out(jsonBody[List[ComboResponseBody]])

  val allEndpoints: List[AnyEndpoint] = List(createComboE, getComboE, updateComboE, deleteComboE, listCombosE)

  def routes[F[_]: Async](
    comboService: ComboService[F],
    jwtSecret:    String
  ): HttpRoutes[F] =

    type Err = (StatusCode, ErrorBody)

    def security(token: String): F[Either[Err, ProviderId]] =
      validateBearer(token, jwtSecret).pure[F]

    def notFound(msg: String): Err   = (StatusCode.NotFound, ErrorBody(msg))
    def badRequest(msg: String): Err = (StatusCode.BadRequest, ErrorBody(msg))
    def forbidden(msg: String): Err  = (StatusCode.Forbidden, ErrorBody(msg))
    def conflict(msg: String): Err   = (StatusCode.Conflict, ErrorBody(msg))

    val createServer = createComboE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => body =>
        (for
          dailyRate    <- Money.fromAmount(body.dailyRate)
                           .fold(err => ComboError.InvalidInput(err).raiseError[F, Money], _.pure[F])
          compositions <- body.itemCompositions.traverse { c =>
                            ItemId.fromString(c.itemId)
                              .fold(err => ComboError.InvalidInput(err).raiseError[F, ComboItemDefinition],
                                    id => ComboItemDefinition(id, c.quantity).pure[F])
                          }
          request       = CreateComboRequest(
                            providerId       = providerId,
                            name             = body.name,
                            description      = body.description,
                            dailyRate        = dailyRate,
                            itemCompositions = compositions
                          )
          comboId      <- comboService.createCombo(request)
        yield Right(CreateComboResponseBody(comboId.value)))
          .handleErrorWith {
            case e: ComboError => Left(badRequest(e.getMessage)).pure[F]
            case _             => Left((StatusCode.InternalServerError, ErrorBody("Internal error"))).pure[F]
          }
      }

    val getServer = getComboE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => comboIdStr =>
        (for
          comboId <- ComboId.fromString(comboIdStr)
                       .fold(err => ComboError.InvalidInput(err).raiseError[F, ComboId], _.pure[F])
          combo   <- comboService.getCombo(comboId, providerId)
        yield Right(toResponseBody(combo)))
          .handleErrorWith {
            case _: ComboError.NotFound  => Left(notFound("Combo not found")).pure[F]
            case _: ComboError.Forbidden => Left(forbidden("Access denied")).pure[F]
            case e: ComboError           => Left(badRequest(e.getMessage)).pure[F]
          }
      }

    val updateServer = updateComboE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => input =>
        val (comboIdStr, body) = input
        (for
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
                            providerId       = providerId,
                            name             = body.name,
                            description      = body.description,
                            dailyRate        = dailyRate,
                            itemCompositions = compositions
                          )
          _            <- comboService.updateCombo(request)
        yield Right(()))
          .handleErrorWith {
            case _: ComboError.HasBookings => Left(conflict("Combo has existing bookings — composition cannot be changed")).pure[F]
            case _: ComboError.NotFound    => Left(notFound("Combo not found")).pure[F]
            case _: ComboError.Forbidden   => Left(forbidden("Access denied")).pure[F]
            case e: ComboError             => Left(badRequest(e.getMessage)).pure[F]
          }
      }

    val deleteServer = deleteComboE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => comboIdStr =>
        (for
          comboId <- ComboId.fromString(comboIdStr)
                       .fold(err => ComboError.InvalidInput(err).raiseError[F, ComboId], _.pure[F])
          _       <- comboService.softDeleteCombo(comboId, providerId)
        yield Right(()))
          .handleErrorWith {
            case _: ComboError.NotFound  => Left(notFound("Combo not found")).pure[F]
            case _: ComboError.Forbidden => Left(forbidden("Access denied")).pure[F]
            case e: ComboError           => Left(badRequest(e.getMessage)).pure[F]
          }
      }

    val listServer = listCombosE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => _ =>
        comboService.listActiveCombos(providerId)
          .map(combos => Right(combos.map(toResponseBody)))
          .handleError(e => Left(badRequest(e.getMessage)))
      }

    Http4sServerInterpreter[F]().toRoutes(List(createServer, getServer, updateServer, deleteServer, listServer))
