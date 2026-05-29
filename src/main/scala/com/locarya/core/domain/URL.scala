package com.locarya.core.domain

final case class URL private (value: String)

object URL {
  def fromString(raw: String): Either[ValidationError, URL] = {
    val urlPattern = "^https?://[^\\s/$.?#].[^\\s]*$".r

    if (raw.trim.isEmpty) {
      Left(InvalidURL("URL cannot be empty"))
    } else if (urlPattern.matches(raw)) {
      Right(URL(raw))
    } else {
      Left(InvalidURL("Invalid URL format - must include scheme (http/https) and host"))
    }
  }
}
