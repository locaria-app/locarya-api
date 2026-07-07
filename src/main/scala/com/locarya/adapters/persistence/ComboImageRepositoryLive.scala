package com.locarya.adapters.persistence

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.ComboImageRepository
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

final class ComboImageRepositoryLive[F[_]: Async] private (xa: Transactor[F])
    extends ComboImageRepository[F]:

  private case class ComboImageRow(
    id:           UUID,
    comboId:      UUID,
    imageUrl:     String,
    displayOrder: Int,
    isPrimary:    Boolean
  ) derives Read

  private def rowToImage(row: ComboImageRow): F[ComboImage] =
    (for
      id  <- ComboImageId.fromString(row.id.toString)
      cid <- ComboId.fromString(row.comboId.toString)
      url <- URL.fromString(row.imageUrl)
    yield ComboImage.reconstruct(id, cid, url, row.displayOrder, row.isPrimary)).fold(
      err => Async[F].raiseError(new RuntimeException(s"DB→domain mapping failed: $err")),
      _.pure[F]
    )

  override def create(image: ComboImage): F[ComboImage] =
    sql"""INSERT INTO combo_images (id, combo_id, image_url, display_order, is_primary)
          VALUES (
            ${UUID.fromString(image.id.value)},
            ${UUID.fromString(image.comboId.value)},
            ${image.imageUrl.value},
            ${image.displayOrder},
            ${image.isPrimary}
          )"""
      .update.run.transact(xa) >> image.pure[F]

  override def findByComboId(comboId: ComboId): F[List[ComboImage]] =
    (fr"SELECT id, combo_id, image_url, display_order, is_primary FROM combo_images" ++
      fr"WHERE combo_id = ${UUID.fromString(comboId.value)} ORDER BY display_order")
      .query[ComboImageRow].to[List].transact(xa).flatMap(_.traverse(rowToImage))

  override def replaceImages(comboId: ComboId, images: List[ComboImage]): F[Unit] =
    val comboUuid = UUID.fromString(comboId.value)
    val delete    = sql"DELETE FROM combo_images WHERE combo_id = $comboUuid".update.run
    val inserts   = images.traverse_ { img =>
      sql"""INSERT INTO combo_images (id, combo_id, image_url, display_order, is_primary)
            VALUES (
              ${UUID.fromString(img.id.value)},
              ${UUID.fromString(img.comboId.value)},
              ${img.imageUrl.value},
              ${img.displayOrder},
              ${img.isPrimary}
            )""".update.run
    }
    (delete >> inserts).transact(xa)

object ComboImageRepositoryLive:
  def make[F[_]: Async](xa: Transactor[F]): ComboImageRepository[F] =
    new ComboImageRepositoryLive[F](xa)
