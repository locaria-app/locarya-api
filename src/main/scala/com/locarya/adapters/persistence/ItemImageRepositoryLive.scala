package com.locarya.adapters.persistence

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.ItemImageRepository
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

final class ItemImageRepositoryLive[F[_]: Async] private (xa: Transactor[F])
    extends ItemImageRepository[F]:

  private case class ItemImageRow(
    id:           UUID,
    itemId:       UUID,
    imageUrl:     String,
    displayOrder: Int,
    isPrimary:    Boolean
  ) derives Read

  private def rowToImage(row: ItemImageRow): F[ItemImage] =
    (for
      id  <- ItemImageId.fromString(row.id.toString)
      iid <- ItemId.fromString(row.itemId.toString)
      url <- URL.fromString(row.imageUrl)
    yield ItemImage.reconstruct(id, iid, url, row.displayOrder, row.isPrimary)).fold(
      err => Async[F].raiseError(new RuntimeException(s"DB→domain mapping failed: $err")),
      _.pure[F]
    )

  override def create(image: ItemImage): F[ItemImage] =
    sql"""INSERT INTO item_images (id, item_id, image_url, display_order, is_primary)
          VALUES (
            ${UUID.fromString(image.id.value)},
            ${UUID.fromString(image.itemId.value)},
            ${image.imageUrl.value},
            ${image.displayOrder},
            ${image.isPrimary}
          )"""
      .update.run.transact(xa) >> image.pure[F]

  override def findByItemId(itemId: ItemId): F[List[ItemImage]] =
    (fr"SELECT id, item_id, image_url, display_order, is_primary FROM item_images" ++
      fr"WHERE item_id = ${UUID.fromString(itemId.value)} ORDER BY display_order")
      .query[ItemImageRow].to[List].transact(xa).flatMap(_.traverse(rowToImage))

  override def replaceImages(itemId: ItemId, images: List[ItemImage]): F[Unit] =
    val itemUuid = UUID.fromString(itemId.value)
    val delete   = sql"DELETE FROM item_images WHERE item_id = $itemUuid".update.run
    val inserts  = images.traverse_ { img =>
      sql"""INSERT INTO item_images (id, item_id, image_url, display_order, is_primary)
            VALUES (
              ${UUID.fromString(img.id.value)},
              ${UUID.fromString(img.itemId.value)},
              ${img.imageUrl.value},
              ${img.displayOrder},
              ${img.isPrimary}
            )""".update.run
    }
    (delete >> inserts).transact(xa)

object ItemImageRepositoryLive:
  def make[F[_]: Async](xa: Transactor[F]): ItemImageRepository[F] =
    new ItemImageRepositoryLive[F](xa)
