package com.locarya.domain.ports

import com.locarya.domain.models.*

trait StorefrontService[F[_]]:
  def getStorefront(slug: StorefrontSlug): F[StorefrontCatalog]
