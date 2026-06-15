package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.StorefrontService
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl

object StorefrontRoutes:

  private case class ImageResponse(url: String, isPrimary: Boolean, displayOrder: Int)
  private given Encoder[ImageResponse] = deriveEncoder

  private case class ItemResponse(
    id:                   String,
    name:                 String,
    description:          String,
    price:                BigDecimal,
    stockQuantity:        Int,
    attendantRequirement: String,
    images:               List[ImageResponse]
  )
  private given Encoder[ItemResponse] = deriveEncoder

  private case class ComboItemCompositionResponse(item: ItemResponse, quantity: Int)
  private given Encoder[ComboItemCompositionResponse] = deriveEncoder

  private case class ComboResponse(
    id:                   String,
    name:                 String,
    description:          String,
    price:                BigDecimal,
    attendantRequirement: String,
    itemCompositions:     List[ComboItemCompositionResponse]
  )
  private given Encoder[ComboResponse] = deriveEncoder

  private case class StorefrontResponse(
    name:   String,
    city:   String,
    state:  String,
    items:  List[ItemResponse],
    combos: List[ComboResponse]
  )
  private given Encoder[StorefrontResponse] = deriveEncoder

  private case class ErrorResponseBody(error: String)
  private given Encoder[ErrorResponseBody] = deriveEncoder

  private def toItemResponse(item: Item, images: List[ItemImage]): ItemResponse =
    ItemResponse(
      id                   = item.id.value,
      name                 = item.name,
      description          = item.description,
      price                = item.dailyRate.amount,
      stockQuantity        = item.stock,
      attendantRequirement = item.attendantRequirement.toString,
      images               = images.map(img => ImageResponse(img.imageUrl.value, img.isPrimary, img.displayOrder))
    )

  def routes[F[_]: Async](storefrontService: StorefrontService[F]): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*

    HttpRoutes.of[F]:
      case GET -> Root / "storefront" / slugStr =>
        (for
          slug    <- StorefrontSlug.fromString(slugStr)
                       .fold(_ => StorefrontError.NotFound(StorefrontSlug.fromString("x").toOption.get).raiseError[F, StorefrontSlug], _.pure[F])
          catalog <- storefrontService.getStorefront(slug)
          itemResps = catalog.items.map(wi => toItemResponse(wi.item, wi.images))
          comboResps = catalog.combos.map { wc =>
                         ComboResponse(
                           id                   = wc.combo.id.value,
                           name                 = wc.combo.name,
                           description          = wc.combo.description,
                           price                = wc.combo.dailyRate.amount,
                           attendantRequirement = wc.combo.attendantRequirement.toString,
                           itemCompositions     = wc.compositions.map { ci =>
                                                    ComboItemCompositionResponse(
                                                      item     = toItemResponse(ci.item, ci.images),
                                                      quantity = ci.quantity
                                                    )
                                                  }
                         )
                       }
          response  = StorefrontResponse(
                        name   = catalog.provider.tradeName,
                        city   = catalog.provider.city,
                        state  = catalog.provider.state,
                        items  = itemResps,
                        combos = comboResps
                      )
          resp     <- Ok(response.asJson)
        yield resp)
          .handleErrorWith {
            case _: StorefrontError.NotFound => NotFound(ErrorResponseBody("Storefront not found").asJson)
          }
