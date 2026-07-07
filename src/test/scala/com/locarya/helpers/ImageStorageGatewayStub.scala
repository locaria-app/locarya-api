package com.locarya.helpers

import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.ProviderId
import com.locarya.domain.ports.{ImageStorageGateway, PresignedUpload}
import java.time.Instant

final class ImageStorageGatewayStub[F[_]: Sync] private extends ImageStorageGateway[F]:
  def presignUpload(providerId: ProviderId, contentType: String): F[PresignedUpload] =
    PresignedUpload(
      objectKey = s"providers/${providerId.value}/stub-object-key",
      uploadUrl = s"https://r2.example.com/upload/${providerId.value}?presigned=true",
      publicUrl = s"https://cdn.example.com/providers/${providerId.value}/stub-object-key",
      expiresAt = Instant.parse("2030-01-01T00:00:00Z")
    ).pure[F]

object ImageStorageGatewayStub:
  def make[F[_]: Sync]: ImageStorageGateway[F] = new ImageStorageGatewayStub[F]
