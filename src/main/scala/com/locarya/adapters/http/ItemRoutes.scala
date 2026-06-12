package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.middleware.AuthMiddleware
import com.locarya.domain.models.*
import com.locarya.domain.ports.{CreateItemRequest, ItemService, UpdateItemRequest}
import io.circe.generic.auto.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl

object ItemRoutes:

  private case class CreateItemBody(
    name:                 String,
    description:          String,
    price:                BigDecimal,
    stock:                Int,
    attendantRequirement: String,
    imageUrls:            List[String]
  )

  private case class UpdateItemBody(
    name:                 String,
    description:          String,
    price:                BigDecimal,
    stock:                Int,
    attendantRequirement: String,
    imageUrls:            List[String]
  )

  private case class ItemResponseBody(
    itemId:               String,
    providerId:           String,
    name:                 String,
    description:          String,
    price:                BigDecimal,
    stock:                Int,
    attendantRequirement: String,
    isActive:             Boolean
  )

  private case class CreateItemResponseBody(itemId: String)
  private case class ErrorResponseBody(error: String)

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
      price                = item.dailyRate.amount,
      stock                = item.stock,
      attendantRequirement = item.attendantRequirement.toString,
      isActive             = item.isActive
    )

  def routes[F[_]: Async](
    itemService: ItemService[F],
    jwtSecret:   String
  ): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*

    given EntityDecoder[F, CreateItemBody] = jsonOf[F, CreateItemBody]
    given EntityDecoder[F, UpdateItemBody] = jsonOf[F, UpdateItemBody]

    AuthMiddleware.withProviderId[F](jwtSecret) { rawProviderId =>

      val providerIdF: F[ProviderId] =
        ProviderId.fromString(rawProviderId)
          .fold(err => ItemError.InvalidInput(err).raiseError[F, ProviderId], _.pure[F])

      HttpRoutes.of[F]:

        case GET -> Root / "dashboard" / "items" =>
          for
            pid      <- providerIdF
            items    <- itemService.listActiveItems(pid)
            response <- Ok(items.map(toResponseBody).asJson)
          yield response

        case req @ POST -> Root / "dashboard" / "items" =>
          req.as[CreateItemBody].flatMap { body =>
            for
              pid   <- providerIdF
              price <- Money.fromAmount(body.price)
                         .fold(err => ItemError.InvalidInput(err).raiseError[F, Money], _.pure[F])
              req2   = CreateItemRequest(
                         providerId           = pid,
                         name                 = body.name,
                         description          = body.description,
                         price                = price,
                         stock                = body.stock,
                         attendantRequirement = parseAttendantRequirement(body.attendantRequirement)
                                                  .fold(_ => AttendantRequirement.Optional, identity),
                         imageUrls            = body.imageUrls
                       )
              itemId <- itemService.createItem(req2)
              resp   <- Created(CreateItemResponseBody(itemId.value).asJson)
            yield resp
          }.handleErrorWith {
            case e: ItemError =>
              BadRequest(ErrorResponseBody(e.getMessage).asJson)
            case _: MalformedMessageBodyFailure =>
              BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
            case _: InvalidMessageBodyFailure =>
              BadRequest(ErrorResponseBody("Invalid request body").asJson)
          }

        case req @ PUT -> Root / "dashboard" / "items" / itemIdStr =>
          req.as[UpdateItemBody].flatMap { body =>
            for
              pid    <- providerIdF
              itemId <- ItemId.fromString(itemIdStr)
                          .fold(err => ItemError.InvalidInput(err).raiseError[F, ItemId], _.pure[F])
              price  <- Money.fromAmount(body.price)
                          .fold(err => ItemError.InvalidInput(err).raiseError[F, Money], _.pure[F])
              req2    = UpdateItemRequest(
                          itemId               = itemId,
                          providerId           = pid,
                          name                 = body.name,
                          description          = body.description,
                          price                = price,
                          stock                = body.stock,
                          attendantRequirement = parseAttendantRequirement(body.attendantRequirement)
                                                   .fold(_ => AttendantRequirement.Optional, identity),
                          imageUrls            = body.imageUrls
                        )
              _      <- itemService.updateItem(req2)
              resp   <- Ok(().asJson)
            yield resp
          }.handleErrorWith {
            case _: ItemError.NotFound  => NotFound(ErrorResponseBody("Item not found").asJson)
            case _: ItemError.Forbidden => Forbidden(ErrorResponseBody("Access denied").asJson)
            case e: ItemError           => BadRequest(ErrorResponseBody(e.getMessage).asJson)
            case _: MalformedMessageBodyFailure =>
              BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
            case _: InvalidMessageBodyFailure =>
              BadRequest(ErrorResponseBody("Invalid request body").asJson)
          }

        case DELETE -> Root / "dashboard" / "items" / itemIdStr =>
          (for
            pid    <- providerIdF
            itemId <- ItemId.fromString(itemIdStr)
                        .fold(err => ItemError.InvalidInput(err).raiseError[F, ItemId], _.pure[F])
            _      <- itemService.deactivateItem(itemId, pid)
            resp   <- Ok(().asJson)
          yield resp)
            .handleErrorWith {
              case _: ItemError.HasBookings => Conflict(ErrorResponseBody("Item has existing bookings").asJson)
              case _: ItemError.NotFound    => NotFound(ErrorResponseBody("Item not found").asJson)
              case _: ItemError.Forbidden   => Forbidden(ErrorResponseBody("Access denied").asJson)
              case e: ItemError             => BadRequest(ErrorResponseBody(e.getMessage).asJson)
            }
    }
