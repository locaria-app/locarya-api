package com.locarya.domain.models

final case class ComboImage private (
  id:           ComboImageId,
  comboId:      ComboId,
  imageUrl:     URL,
  displayOrder: Int,
  isPrimary:    Boolean
)

object ComboImage:
  val MaxImages = 5

  def reconstruct(
    id:           ComboImageId,
    comboId:      ComboId,
    imageUrl:     URL,
    displayOrder: Int,
    isPrimary:    Boolean
  ): ComboImage = ComboImage(id, comboId, imageUrl, displayOrder, isPrimary)

  def create(
    comboId:   ComboId,
    imageUrls: List[String]
  ): Either[ValidationError, List[ComboImage]] =
    if imageUrls.isEmpty then
      Left(InvalidComboImage("At least one image URL is required"))
    else if imageUrls.size > MaxImages then
      Left(InvalidComboImage(s"Maximum $MaxImages images allowed, got ${imageUrls.size}"))
    else
      imageUrls.zipWithIndex.foldLeft[Either[ValidationError, List[ComboImage]]](Right(List.empty)) {
        case (Left(err), _) => Left(err)
        case (Right(acc), (rawUrl, idx)) =>
          URL.fromString(rawUrl).map { url =>
            acc :+ ComboImage(
              id           = ComboImageId.generate,
              comboId      = comboId,
              imageUrl     = url,
              displayOrder = idx + 1,
              isPrimary    = idx == 0
            )
          }
      }
