package com.locarya.adapters.external

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.config.R2Config
import com.locarya.domain.models.ProviderId
import com.locarya.domain.ports.{ImageStorageGateway, PresignedUpload}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.time.Duration
import java.util.UUID

final class S3ImageStorageLive[F[_]: Async] private (
  presigner:     S3Presigner,
  bucketName:    String,
  publicBaseUrl: String
) extends ImageStorageGateway[F]:

  def presignUpload(providerId: ProviderId, contentType: String): F[PresignedUpload] =
    Async[F].blocking {
      val objectKey = s"providers/${providerId.value}/${UUID.randomUUID()}"
      val putReq = PutObjectRequest.builder()
        .bucket(bucketName)
        .key(objectKey)
        .contentType(contentType)
        .build()
      val presignReq = PutObjectPresignRequest.builder()
        .signatureDuration(Duration.ofMinutes(15))
        .putObjectRequest(putReq)
        .build()
      val presigned = presigner.presignPutObject(presignReq)
      PresignedUpload(
        objectKey = objectKey,
        uploadUrl = presigned.url().toString,
        publicUrl = s"$publicBaseUrl/$objectKey",
        expiresAt = presigned.expiration()
      )
    }

object S3ImageStorageLive:
  def make[F[_]: Async](config: R2Config): S3ImageStorageLive[F] =
    val credentials = AwsBasicCredentials.create(config.accessKeyId, config.secretAccessKey)
    val presigner = S3Presigner.builder()
      .credentialsProvider(StaticCredentialsProvider.create(credentials))
      .endpointOverride(URI.create(config.endpoint))
      .region(Region.US_EAST_1)
      .build()
    new S3ImageStorageLive[F](presigner, config.bucketName, config.publicBaseUrl)
