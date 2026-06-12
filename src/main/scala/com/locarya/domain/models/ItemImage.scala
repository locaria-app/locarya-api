package com.locarya.domain.models

final case class ItemImage private (
  id: ItemImageId,
  itemId: ItemId,
  imageUrl: URL,
  displayOrder: Int,
  isPrimary: Boolean
)

object ItemImage:
  val MaxImages = 5

  def create(
    itemId: ItemId,
    imageUrls: List[String]
  ): Either[ValidationError, List[ItemImage]] =
    if imageUrls.isEmpty then
      Left(InvalidItemImage("At least one image URL is required"))
    else if imageUrls.size > MaxImages then
      Left(InvalidItemImage(s"Maximum $MaxImages images allowed, got ${imageUrls.size}"))
    else
      imageUrls.zipWithIndex.foldLeft[Either[ValidationError, List[ItemImage]]](Right(List.empty)) {
        case (Left(err), _) => Left(err)
        case (Right(acc), (rawUrl, idx)) =>
          URL.fromString(rawUrl).map { url =>
            acc :+ ItemImage(
              id           = ItemImageId.generate,
              itemId       = itemId,
              imageUrl     = url,
              displayOrder = idx + 1,
              isPrimary    = idx == 0
            )
          }
      }
