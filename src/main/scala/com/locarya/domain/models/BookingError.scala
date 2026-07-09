package com.locarya.domain.models

sealed abstract class BookingError(message: String) extends RuntimeException(message)

object BookingError:
  final case class InvalidInput(error: ValidationError)
      extends BookingError(error.message)

  final case class ProviderNotFound(slug: StorefrontSlug)
      extends BookingError(s"Storefront '${slug.value}' not found")

  final case class ProviderIdNotFound(id: ProviderId)
      extends BookingError(s"Provider '${id.value}' not found")

  final case class ItemsUnavailable(unavailable: List[ItemAvailability])
      extends BookingError(
        s"Items unavailable: ${unavailable.map(_.id.value).mkString(", ")}"
      )

  final case class BookingNotFound(id: BookingId)
      extends BookingError(s"Booking '${id.value}' not found")

  final case class MonitorRequiredWithoutOverride(lines: List[BookingLineRef])
      extends BookingError(
        s"Monitor required but not assigned for lines: ${lines.map {
          case BookingLineRef.IndividualLine(id) => id.value
          case BookingLineRef.ComboLine(id)      => id.value
        }.mkString(", ")}. Resend with overrideMonitorCheck=true to confirm anyway."
      )
