package com.locarya.domain.models

sealed abstract class ProviderError(message: String) extends RuntimeException(message)

object ProviderError:
  final case class NotFound(id: ProviderId)
      extends ProviderError(s"Provider '${id.value}' not found")
