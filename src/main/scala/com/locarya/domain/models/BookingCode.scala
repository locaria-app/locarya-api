package com.locarya.domain.models

final case class BookingCode private (value: String)

object BookingCode:
  private val Pattern = """^LCR-[A-Z0-9]{6}$""".r

  def fromString(raw: String): Either[ValidationError, BookingCode] =
    if Pattern.matches(raw) then Right(BookingCode(raw))
    else Left(InvalidBookingCode(s"Invalid booking code: '$raw' — expected LCR-[A-Z0-9]{6}"))

  def generate: BookingCode =
    val chars  = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val rnd    = new scala.util.Random
    val suffix = (1 to 6).map(_ => chars(rnd.nextInt(36))).mkString
    BookingCode(s"LCR-$suffix")
