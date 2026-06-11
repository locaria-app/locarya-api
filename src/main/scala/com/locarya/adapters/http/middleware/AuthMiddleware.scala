package com.locarya.adapters.http.middleware

import cats.data.OptionT
import cats.effect.Sync
import org.http4s.*
import org.http4s.headers.Authorization
import pdi.jwt.{JwtAlgorithm, JwtCirce}

import scala.util.{Failure, Success}

object AuthMiddleware:

  def apply[F[_]: Sync](secret: String)(inner: HttpRoutes[F]): HttpRoutes[F] =
    HttpRoutes[F] { req =>
      extractBearer(req) match
        case None =>
          OptionT.pure[F](Response[F](Status.Unauthorized))
        case Some(token) =>
          JwtCirce.decodeJson(token, secret, Seq(JwtAlgorithm.HS256)) match
            case Failure(_) => OptionT.pure[F](Response[F](Status.Unauthorized))
            case Success(_) => inner(req)
    }

  private def extractBearer[F[_]](req: Request[F]): Option[String] =
    req.headers.get[Authorization].collect {
      case Authorization(Credentials.Token(AuthScheme.Bearer, token)) => token
    }
