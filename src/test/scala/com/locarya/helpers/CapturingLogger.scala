package com.locarya.helpers

import cats.effect.{Async, Ref}
import cats.syntax.all.*
import org.typelevel.log4cats.Logger

final class CapturingLogger[F[_]: Async] private (messages: Ref[F, List[String]])
    extends Logger[F]:

  def error(t: Throwable)(message: => String): F[Unit] = messages.update(_ :+ s"ERROR: $message")
  def warn(t: Throwable)(message: => String): F[Unit]  = messages.update(_ :+ s"WARN: $message")
  def info(t: Throwable)(message: => String): F[Unit]  = messages.update(_ :+ s"INFO: $message")
  def debug(t: Throwable)(message: => String): F[Unit] = messages.update(_ :+ s"DEBUG: $message")
  def trace(t: Throwable)(message: => String): F[Unit] = messages.update(_ :+ s"TRACE: $message")
  def error(message: => String): F[Unit]               = messages.update(_ :+ s"ERROR: $message")
  def warn(message: => String): F[Unit]                = messages.update(_ :+ s"WARN: $message")
  def info(message: => String): F[Unit]                = messages.update(_ :+ s"INFO: $message")
  def debug(message: => String): F[Unit]               = messages.update(_ :+ s"DEBUG: $message")
  def trace(message: => String): F[Unit]               = messages.update(_ :+ s"TRACE: $message")

  def getMessages: F[List[String]] = messages.get

object CapturingLogger:
  def make[F[_]: Async]: F[CapturingLogger[F]] =
    Ref.of[F, List[String]](Nil).map(new CapturingLogger(_))
