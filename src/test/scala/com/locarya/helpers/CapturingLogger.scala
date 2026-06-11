package com.locarya.helpers

import cats.effect.{IO, Ref}
import org.typelevel.log4cats.Logger

object CapturingLogger:

  def make: IO[(Logger[IO], IO[List[String]])] =
    Ref.of[IO, List[String]](List.empty).map { ref =>
      val logger = new Logger[IO]:
        def error(t: Throwable)(message: => String): IO[Unit] = ref.update(_ :+ message)
        def warn(t: Throwable)(message: => String): IO[Unit]  = ref.update(_ :+ message)
        def info(t: Throwable)(message: => String): IO[Unit]  = ref.update(_ :+ message)
        def debug(t: Throwable)(message: => String): IO[Unit] = ref.update(_ :+ message)
        def trace(t: Throwable)(message: => String): IO[Unit] = ref.update(_ :+ message)
        def error(message: => String): IO[Unit] = ref.update(_ :+ message)
        def warn(message: => String): IO[Unit]  = ref.update(_ :+ message)
        def info(message: => String): IO[Unit]  = ref.update(_ :+ message)
        def debug(message: => String): IO[Unit] = ref.update(_ :+ message)
        def trace(message: => String): IO[Unit] = ref.update(_ :+ message)
      (logger, ref.get)
    }
