package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.TapirSupport.{ErrorBody, securedBase, validateBearer}
import com.locarya.domain.models.*
import com.locarya.domain.ports.{AssignAttendantsRequest, AttendantService, CreateAttendantRequest, RemoveAttendantFromLineRequest, UpdateAttendantRequest}
import io.circe.generic.auto.*
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.AnyEndpoint
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.Http4sServerInterpreter

object AttendantRoutes:

  private case class CreateAttendantBody(name: String, phone: String)
  private case class UpdateAttendantBody(name: String, phone: String)
  private case class AssignAttendantsBody(
    attendantIds: List[String],
    itemId:       Option[String] = None,
    comboId:      Option[String] = None
  )

  private case class AttendantResponse(
    attendantId: String,
    providerId:  String,
    name:        String,
    phone:       String,
    isActive:    Boolean
  )

  private case class CreateAttendantResponse(attendantId: String)

  private def toResponse(a: Attendant): AttendantResponse =
    AttendantResponse(a.id.value, a.providerId.value, a.name, a.phone, a.isActive)

  private val listAttendantsE = securedBase.get
    .in("dashboard" / "attendants")
    .out(jsonBody[List[AttendantResponse]])

  private val createAttendantE = securedBase.post
    .in("dashboard" / "attendants")
    .in(jsonBody[CreateAttendantBody])
    .out(statusCode(StatusCode.Created).and(jsonBody[CreateAttendantResponse]))

  private val updateAttendantE = securedBase.put
    .in("dashboard" / "attendants" / path[String]("attendantId"))
    .in(jsonBody[UpdateAttendantBody])

  private val deleteAttendantE = securedBase.delete
    .in("dashboard" / "attendants" / path[String]("attendantId"))

  private val assignAttendantsE = securedBase.put
    .in("dashboard" / "bookings" / path[String]("bookingId") / "attendants")
    .in(jsonBody[AssignAttendantsBody])

  private val removeAttendantFromLineE = securedBase.delete
    .in("dashboard" / "bookings" / path[String]("bookingId") / "attendants" / path[String]("attendantId"))
    .in(query[Option[String]]("itemId"))
    .in(query[Option[String]]("comboId"))

  val allEndpoints: List[AnyEndpoint] =
    List(listAttendantsE, createAttendantE, updateAttendantE, deleteAttendantE, assignAttendantsE, removeAttendantFromLineE)

  def routes[F[_]: Async](
    attendantService: AttendantService[F],
    jwtSecret:        String
  ): HttpRoutes[F] =

    type Err = (StatusCode, ErrorBody)

    def security(token: String): F[Either[Err, ProviderId]] =
      validateBearer(token, jwtSecret).pure[F]

    def notFound(msg: String): Err   = (StatusCode.NotFound, ErrorBody(msg))
    def badRequest(msg: String): Err = (StatusCode.BadRequest, ErrorBody(msg))
    def forbidden(msg: String): Err  = (StatusCode.Forbidden, ErrorBody(msg))

    def parseLineRef(itemIdOpt: Option[String], comboIdOpt: Option[String]): F[BookingLineRef] =
      (itemIdOpt, comboIdOpt) match
        case (Some(iStr), None) =>
          ItemId.fromString(iStr)
            .fold(err => AttendantError.InvalidInput(err).raiseError[F, BookingLineRef],
                  id  => BookingLineRef.IndividualLine(id).pure[F])
        case (None, Some(cStr)) =>
          ComboId.fromString(cStr)
            .fold(err => AttendantError.InvalidInput(err).raiseError[F, BookingLineRef],
                  id  => BookingLineRef.ComboLine(id).pure[F])
        case _ =>
          AttendantError.InvalidInput(InvalidEntityId("Exactly one of itemId or comboId must be provided")).raiseError[F, BookingLineRef]

    val listServer = listAttendantsE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => _ =>
        attendantService.listActiveAttendants(providerId)
          .map(list => Right(list.map(toResponse)))
          .handleErrorWith {
            case e: AttendantError => Left(badRequest(e.getMessage)).pure[F]
            case _                 => Left((StatusCode.InternalServerError, ErrorBody("Internal error"))).pure[F]
          }
      }

    val createServer = createAttendantE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => body =>
        attendantService.createAttendant(CreateAttendantRequest(providerId, body.name, body.phone))
          .map(id => Right(CreateAttendantResponse(id.value)))
          .handleErrorWith {
            case e: AttendantError => Left(badRequest(e.getMessage)).pure[F]
            case _                 => Left((StatusCode.InternalServerError, ErrorBody("Internal error"))).pure[F]
          }
      }

    val updateServer = updateAttendantE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => input =>
        val (attendantIdStr, body) = input
        (for
          attendantId <- AttendantId.fromString(attendantIdStr)
                           .fold(err => AttendantError.InvalidInput(err).raiseError[F, AttendantId], _.pure[F])
          req2         = UpdateAttendantRequest(attendantId, providerId, body.name, body.phone)
          _           <- attendantService.updateAttendant(req2)
        yield Right(()))
          .handleErrorWith {
            case _: AttendantError.AttendantNotFound => Left(notFound("Attendant not found")).pure[F]
            case _: AttendantError.Forbidden         => Left(forbidden("Access denied")).pure[F]
            case e: AttendantError                   => Left(badRequest(e.getMessage)).pure[F]
          }
      }

    val deleteServer = deleteAttendantE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => attendantIdStr =>
        (for
          attendantId <- AttendantId.fromString(attendantIdStr)
                           .fold(err => AttendantError.InvalidInput(err).raiseError[F, AttendantId], _.pure[F])
          _           <- attendantService.deactivateAttendant(attendantId, providerId)
        yield Right(()))
          .handleErrorWith {
            case _: AttendantError.AttendantNotFound => Left(notFound("Attendant not found")).pure[F]
            case _: AttendantError.Forbidden         => Left(forbidden("Access denied")).pure[F]
            case e: AttendantError                   => Left(badRequest(e.getMessage)).pure[F]
          }
      }

    val assignServer = assignAttendantsE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => input =>
        val (bookingIdStr, body) = input
        (for
          bookingId    <- BookingId.fromString(bookingIdStr)
                            .fold(err => AttendantError.InvalidInput(err).raiseError[F, BookingId], _.pure[F])
          lineRef      <- parseLineRef(body.itemId, body.comboId)
          attendantIds <- body.attendantIds.traverse { idStr =>
                            AttendantId.fromString(idStr)
                              .fold(err => AttendantError.InvalidInput(err).raiseError[F, AttendantId], _.pure[F])
                          }
          req2          = AssignAttendantsRequest(bookingId, providerId, lineRef, attendantIds)
          _            <- attendantService.assignAttendants(req2)
        yield Right(()))
          .handleErrorWith {
            case _: AttendantError.AttendantNotFound => Left(notFound("Attendant not found")).pure[F]
            case _: AttendantError.BookingNotFound   => Left(notFound("Booking not found")).pure[F]
            case _: AttendantError.Forbidden         => Left(forbidden("Access denied")).pure[F]
            case _: AttendantError.AttendantInactive => Left(badRequest("Attendant is inactive")).pure[F]
            case e: AttendantError                   => Left(badRequest(e.getMessage)).pure[F]
          }
      }

    val removeServer = removeAttendantFromLineE.serverSecurityLogic[ProviderId, F](security)
      .serverLogic { providerId => input =>
        val (bookingIdStr, attendantIdStr, itemIdOpt, comboIdOpt) = input
        (for
          bookingId   <- BookingId.fromString(bookingIdStr)
                           .fold(err => AttendantError.InvalidInput(err).raiseError[F, BookingId], _.pure[F])
          attendantId <- AttendantId.fromString(attendantIdStr)
                           .fold(err => AttendantError.InvalidInput(err).raiseError[F, AttendantId], _.pure[F])
          lineRef     <- parseLineRef(itemIdOpt, comboIdOpt)
          req2         = RemoveAttendantFromLineRequest(bookingId, providerId, lineRef, attendantId)
          _           <- attendantService.removeAttendantFromLine(req2)
        yield Right(()))
          .handleErrorWith {
            case _: AttendantError.AttendantNotFound => Left(notFound("Attendant not found")).pure[F]
            case _: AttendantError.Forbidden         => Left(forbidden("Access denied")).pure[F]
            case e: AttendantError                   => Left(badRequest(e.getMessage)).pure[F]
          }
      }

    Http4sServerInterpreter[F]().toRoutes(
      List(listServer, createServer, updateServer, deleteServer, assignServer, removeServer)
    )
