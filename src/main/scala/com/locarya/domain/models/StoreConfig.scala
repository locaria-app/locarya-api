package com.locarya.domain.models

case class StoreConfig(
  primaryColor:    Option[String] = Some("#F26A1B"),
  logoUrl:         Option[String] = None,
  whatsappNumber:  Option[String] = None,
  phoneNumber:     Option[String] = None,
  businessHours:   Option[String] = None,
  tagline:         Option[String] = None
)
