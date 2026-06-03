package com.locarya.infrastructure.repository

import doobie.*
import com.locarya.core.domain.*

object DoobieMeta {

  // Entity IDs (UUID-backed strings)
  given Meta[ProviderId] = Meta[String].imap(id => ProviderId.fromString(id).toOption.get)(_.value)
  given Meta[CustomerId] = Meta[String].imap(id => CustomerId.fromString(id).toOption.get)(_.value)
  given Meta[ItemId] = Meta[String].imap(id => ItemId.fromString(id).toOption.get)(_.value)
  given Meta[ComboId] = Meta[String].imap(id => ComboId.fromString(id).toOption.get)(_.value)
  given Meta[BookingId] = Meta[String].imap(id => BookingId.fromString(id).toOption.get)(_.value)
  given Meta[AttendantId] = Meta[String].imap(id => AttendantId.fromString(id).toOption.get)(_.value)

  // Value objects - string-based
  given Meta[Email] = Meta[String].imap(e => Email.fromString(e).toOption.get)(_.value)
  given Meta[CPF] = Meta[String].imap(c => CPF.fromString(c).toOption.get)(_.value)
  given Meta[CNPJ] = Meta[String].imap(c => CNPJ.fromString(c).toOption.get)(_.value)

  // Money (BigDecimal wrapper)
  given Meta[Money] = Meta[BigDecimal].imap(m => Money.fromAmount(m).toOption.get)(_.amount)

  // BookingStatus enum
  given Meta[BookingStatus] = Meta[String].imap {
    case "Pending" => BookingStatus.Pending
    case "Confirmed" => BookingStatus.Confirmed
    case "InProgress" => BookingStatus.InProgress
    case "Completed" => BookingStatus.Completed
    case "Cancelled" => BookingStatus.Cancelled
    case other => throw new IllegalArgumentException(s"Unknown BookingStatus: $other")
  } {
    case BookingStatus.Pending => "Pending"
    case BookingStatus.Confirmed => "Confirmed"
    case BookingStatus.InProgress => "InProgress"
    case BookingStatus.Completed => "Completed"
    case BookingStatus.Cancelled => "Cancelled"
  }

  // ContractStatus enum
  given Meta[ContractStatus] = Meta[String].imap {
    case "Active" => ContractStatus.Active
    case "Suspended" => ContractStatus.Suspended
    case "Cancelled" => ContractStatus.Cancelled
    case other => throw new IllegalArgumentException(s"Unknown ContractStatus: $other")
  } {
    case ContractStatus.Active => "Active"
    case ContractStatus.Suspended => "Suspended"
    case ContractStatus.Cancelled => "Cancelled"
  }

  // AttendantRequirement enum
  given Meta[AttendantRequirement] = Meta[String].imap {
    case "Required" => AttendantRequirement.Required
    case "Optional" => AttendantRequirement.Optional
    case "NotAllowed" => AttendantRequirement.NotAllowed
    case other => throw new IllegalArgumentException(s"Unknown AttendantRequirement: $other")
  } {
    case AttendantRequirement.Required => "Required"
    case AttendantRequirement.Optional => "Optional"
    case AttendantRequirement.NotAllowed => "NotAllowed"
  }

  // TaxId - conditional read based on which column is non-null
  // (Used in complex queries where we read both cpf and cnpj columns)
  def readTaxId(cpf: Option[String], cnpj: Option[String]): TaxId = {
    (cpf, cnpj) match {
      case (Some(c), None) => TaxId.CPFTaxId(CPF.fromString(c).toOption.get)
      case (None, Some(c)) => TaxId.CNPJTaxId(CNPJ.fromString(c).toOption.get)
      case _ => throw new IllegalStateException("TaxId must have exactly one of CPF or CNPJ")
    }
  }
}
