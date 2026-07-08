package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.TapirSupport.{ErrorBody, publicBase}
import com.locarya.domain.models.*
import com.locarya.domain.ports.StorefrontService
import io.circe.Codec
import io.circe.generic.auto.*
import io.circe.generic.semiauto.deriveCodec
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.Http4sServerInterpreter

object StorefrontRoutes:

  private case class StoreConfigResponse(
    primaryColor:   Option[String],
    logoUrl:        Option[String],
    whatsappNumber: Option[String],
    phoneNumber:    Option[String],
    businessHours:  Option[String],
    tagline:        Option[String]
  )
  private given Codec[StoreConfigResponse]  = deriveCodec
  private given Schema[StoreConfigResponse] = Schema.derived

  private case class ImageResponse(url: String, isPrimary: Boolean, displayOrder: Int)
  private given Codec[ImageResponse]         = deriveCodec
  private given Schema[ImageResponse]        = Schema.derived

  private case class ItemResponse(
    id:              String,
    name:            String,
    description:     String,
    price:           BigDecimal,
    stockQuantity:   Int,
    requiresMonitor: Boolean,
    images:          List[ImageResponse]
  )
  private given Codec[ItemResponse]          = deriveCodec
  private given Schema[ItemResponse]         = Schema.derived

  private case class ComboItemCompositionResponse(item: ItemResponse, quantity: Int)
  private given Codec[ComboItemCompositionResponse]  = deriveCodec
  private given Schema[ComboItemCompositionResponse] = Schema.derived

  private case class ComboResponse(
    id:               String,
    name:             String,
    description:      String,
    price:            BigDecimal,
    requiresMonitor:  Boolean,
    itemCompositions: List[ComboItemCompositionResponse]
  )
  private given Codec[ComboResponse]         = deriveCodec
  private given Schema[ComboResponse]        = Schema.derived

  private case class StorefrontResponse(
    name:                 String,
    city:                 String,
    state:                String,
    items:                List[ItemResponse],
    combos:               List[ComboResponse],
    config:               StoreConfigResponse,
    onlinePaymentEnabled: Boolean
  )
  private given Codec[StorefrontResponse]    = deriveCodec
  private given Schema[StorefrontResponse]   = Schema.derived

  private def toItemResponse(item: Item, images: List[ItemImage]): ItemResponse =
    ItemResponse(
      id              = item.id.value,
      name            = item.name,
      description     = item.description,
      price           = item.dailyRate.amount,
      stockQuantity   = item.stock,
      requiresMonitor = item.requiresMonitor,
      images          = images.map(img => ImageResponse(img.imageUrl.value, img.isPrimary, img.displayOrder))
    )

  private val storefrontE = publicBase.get
    .in("storefront" / path[String]("slug"))
    .out(jsonBody[StorefrontResponse])
    .errorOut(statusCode.and(jsonBody[ErrorBody]))

  val allEndpoints: List[AnyEndpoint] = List(storefrontE)

  def routes[F[_]: Async](storefrontService: StorefrontService[F]): HttpRoutes[F] =

    val server = storefrontE.serverLogic[F] { slugStr =>
      StorefrontSlug.fromString(slugStr) match
        case Left(_) => Left((StatusCode.NotFound, ErrorBody("Storefront not found"))).pure[F]
        case Right(slug) =>
          (for
            catalog <- storefrontService.getStorefront(slug)
            itemResps  = catalog.items.map(wi => toItemResponse(wi.item, wi.images))
            comboResps = catalog.combos.map { wc =>
                           ComboResponse(
                             id               = wc.combo.id.value,
                             name             = wc.combo.name,
                             description      = wc.combo.description,
                             price            = wc.combo.dailyRate.amount,
                             requiresMonitor  = wc.combo.requiresMonitor,
                             itemCompositions = wc.compositions.map { ci =>
                                                 ComboItemCompositionResponse(
                                                   item     = toItemResponse(ci.item, ci.images),
                                                   quantity = ci.quantity
                                                 )
                                               }
                           )
                         }
            sc         = catalog.provider.storeConfig
            response   = StorefrontResponse(
                           name                 = catalog.provider.tradeName,
                           city                 = catalog.provider.city,
                           state                = catalog.provider.state,
                           items                = itemResps,
                           combos               = comboResps,
                           config               = StoreConfigResponse(
                             primaryColor   = sc.primaryColor,
                             logoUrl        = sc.logoUrl,
                             whatsappNumber = sc.whatsappNumber,
                             phoneNumber    = sc.phoneNumber,
                             businessHours  = sc.businessHours,
                             tagline        = sc.tagline
                           ),
                           onlinePaymentEnabled = catalog.provider.planTier == PlanTier.Premium && catalog.provider.walletId.isDefined
                         )
          yield Right(response))
            .handleErrorWith {
              case _: StorefrontError.NotFound => Left((StatusCode.NotFound, ErrorBody("Storefront not found"))).pure[F]
            }
    }

    Http4sServerInterpreter[F]().toRoutes(List(server))
