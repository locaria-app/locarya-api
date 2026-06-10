package com.locarya.domain.models

import java.util.UUID

final case class StorefrontSlug private (value: String)

object StorefrontSlug:

  def generate(tradeName: String): StorefrontSlug =
    val base   = slugify(tradeName)
    val suffix = UUID.randomUUID().toString.replace("-", "").take(6)
    StorefrontSlug(s"$base-$suffix")

  def fromString(raw: String): Either[ValidationError, StorefrontSlug] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Left(InvalidStorefrontSlug("Storefront slug cannot be empty"))
    else Right(StorefrontSlug(trimmed))

  private def slugify(s: String): String =
    val normalized = s.toLowerCase
      .replaceAll("[àáâãäå]", "a")
      .replaceAll("[èéêë]",   "e")
      .replaceAll("[ìíîï]",   "i")
      .replaceAll("[òóôõö]",  "o")
      .replaceAll("[ùúûü]",   "u")
      .replaceAll("[ç]",      "c")
      .replaceAll("[ñ]",      "n")
    normalized
      .replaceAll("[^a-z0-9\\s]", " ")
      .trim
      .replaceAll("\\s+", "-")
      .replaceAll("-+", "-")
      .stripPrefix("-")
      .stripSuffix("-")
