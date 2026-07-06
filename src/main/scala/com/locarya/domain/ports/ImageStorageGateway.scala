package com.locarya.domain.ports

import com.locarya.domain.models.ProviderId
import java.time.Instant

final case class PresignedUpload(
  objectKey:  String,
  uploadUrl:  String,
  publicUrl:  String,
  expiresAt:  Instant
)

trait ImageStorageGateway[F[_]]:
  def presignUpload(providerId: ProviderId, contentType: String): F[PresignedUpload]
