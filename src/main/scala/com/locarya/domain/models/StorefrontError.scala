package com.locarya.domain.models

sealed abstract class StorefrontError(message: String) extends RuntimeException(message)

object StorefrontError:
  final case class NotFound(slug: StorefrontSlug)
      extends StorefrontError(s"Storefront '${slug.value}' not found")
