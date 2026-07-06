package com.locarya.domain.models

final case class Password private (value: String)

object Password:
  private val MinLength = 8

  def fromString(raw: String): Either[ValidationError, Password] =
    if raw.length < MinLength then
      Left(InvalidPassword(s"Password must be at least $MinLength characters"))
    else if !raw.exists(_.isUpper) then
      Left(InvalidPassword("Password must contain at least one uppercase letter"))
    else if !raw.exists(_.isLower) then
      Left(InvalidPassword("Password must contain at least one lowercase letter"))
    else if !raw.exists(_.isDigit) then
      Left(InvalidPassword("Password must contain at least one number"))
    else
      Right(Password(raw))
