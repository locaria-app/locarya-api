package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.TapirSupport.{ErrorBody, securedBase, validateBearer}
import com.locarya.domain.models.*
import com.locarya.domain.ports.{CreateItemRequest, ItemService, UpdateItemRequest}
import io.circe.generic.auto.*
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.AnyEndpoint
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.Http4sServerInterpreter

object ItemRoutes:

  private case class CreateItemBody(
    name:                 String,
    description:          String,
    dailyRate:            BigDecimal,
    stock:                Int,
    attendantRequirement: String,
    imageUrls:            List[String]
  )

  private case class UpdateItemBody(
    name:                 String,
    description:          String,
    dailyRate:            BigDecimal,
    stock:                Int,
    attendantRequirement: String,
    imageUrls:            List[String]
  )

  private case class ItemResponseBody(
    itemId:               String,
    providerId:           String,
    name:                 String,
    description:          String,
    dailyRate:            BigDecimal,
    stock:                Int,
    attendantRequirement: String,
    isActive:             Boolean
  )

  private case class CreateItemResponseBody(itemId: String)

  private def parseAttendantRequirement(s: String): Either[String, AttendantRequirement] =
    s match
      case "Required"   => Right(AttendantRequirement.Required)
      case "Optional"   => Right(AttendantRequirement.Optional)
      case "NotAllowed" => Right(AttendantRequirement.NotAllowed)
      case other        => Left(s"Unknown attendantRequirement: $other")

  private def toResponseBody(item: Item): ItemResponseBody =
    ItemResponseBody(
      itemId               = item.id.value,
      providerId           = item.providerId.value,
      name                 = item.name,
      description          = item.description,
      dailyRate            = item.dailyRate.amount,
      stock                = item.stock,
      attendantRequirement = item.attendantRequirement.toString,
      isActive             = item.isActive
    )

  // Endpoint definitions — used both for Swagger docs and for routing
  private val listE = securedBase.get
    .in("dashboard" / "items")
    .out(jsonBody[List[ItemResponseBody]])

  private val createE = securedBase.post
    .in("dashboard" / "items")
    .in(jsonBody[CreateItemBody])
    .out(statusCode(StatusCode.Created).and(jsonBody[CreateItemResponseBody]))

  private val updateE = securedBase.put
    .in("dashboard" / "items" / path[String]("itemId"))
    .in(jsonBody[UpdateItemBody])

  private val deactivateE = securedBase.delete
    .in("dashboard" / "items" / path[String]("itemId"))

  val allEndpoints: List[AnyEndpoint] = List(listE, createE, updateE, deactivateE)

  def routes[F[_]: Async](
    itemService: ItemService[F],
    jwtSecret:   String
  ): HttpRoutes[F] =

    type Err = (StatusCode, ErrorBody)

    def security(token: String): F[Either[Err, ProviderId]] =
      validateBearer(token, jwtSecret).pure[F]

    def badRequest(e: Throwable): Err =
      (StatusCode.BadRequest, ErrorBody(e.getMessage))

    val listServer = listE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => _ =>
        itemService.listActiveItems(providerId)
          .map(items => Right(items.map(toResponseBody)))
          .handleError(e => Left(badRequest(e)))
      }

    val createServer = createE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => body =>
        (for
          dailyRate <- Money.fromAmount(body.dailyRate)
                         .fold(err => ItemError.InvalidInput(err).raiseError[F, Money], _.pure[F])
          req2       = CreateItemRequest(
                         providerId           = providerId,
                         name                 = body.name,
                         description          = body.description,
                         dailyRate            = dailyRate,
                         stock                = body.stock,
                         attendantRequirement = parseAttendantRequirement(body.attendantRequirement)
                                                  .fold(_ => AttendantRequirement.Optional, identity),
                         imageUrls            = body.imageUrls
                       )
          itemId    <- itemService.createItem(req2)
        yield Right(CreateItemResponseBody(itemId.value)))
          .handleError(e => Left(badRequest(e)))
      }

    val updateServer = updateE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => input =>
        val (itemIdStr, body) = input
        (for
          itemId    <- ItemId.fromString(itemIdStr)
                         .fold(err => ItemError.InvalidInput(err).raiseError[F, ItemId], _.pure[F])
          dailyRate <- Money.fromAmount(body.dailyRate)
                         .fold(err => ItemError.InvalidInput(err).raiseError[F, Money], _.pure[F])
          req2       = UpdateItemRequest(
                         itemId               = itemId,
                         providerId           = providerId,
                         name                 = body.name,
                         description          = body.description,
                         dailyRate            = dailyRate,
                         stock                = body.stock,
                         attendantRequirement = parseAttendantRequirement(body.attendantRequirement)
                                                  .fold(_ => AttendantRequirement.Optional, identity),
                         imageUrls            = body.imageUrls
                       )
          _         <- itemService.updateItem(req2)
        yield Right(()))
          .handleErrorWith {
            case _: ItemError.NotFound  => Left((StatusCode.NotFound, ErrorBody("Item not found"))).pure[F]
            case _: ItemError.Forbidden => Left((StatusCode.Forbidden, ErrorBody("Access denied"))).pure[F]
            case e: ItemError           => Left(badRequest(e)).pure[F]
          }
      }

    val deactivateServer = deactivateE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => itemIdStr =>
        (for
          itemId <- ItemId.fromString(itemIdStr)
                      .fold(err => ItemError.InvalidInput(err).raiseError[F, ItemId], _.pure[F])
          _      <- itemService.deactivateItem(itemId, providerId)
        yield Right(()))
          .handleErrorWith {
            case _: ItemError.HasBookings => Left((StatusCode.Conflict, ErrorBody("Item has existing bookings"))).pure[F]
            case _: ItemError.NotFound    => Left((StatusCode.NotFound, ErrorBody("Item not found"))).pure[F]
            case _: ItemError.Forbidden   => Left((StatusCode.Forbidden, ErrorBody("Access denied"))).pure[F]
            case e: ItemError             => Left(badRequest(e)).pure[F]
          }
      }

    Http4sServerInterpreter[F]().toRoutes(
      List(listServer, createServer, updateServer, deactivateServer)
    )
