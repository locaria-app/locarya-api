package com.locarya.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.adapters.http.middleware.AuthMiddleware
import com.locarya.domain.models.*
import com.locarya.domain.ports.{AssignAttendantsRequest, AttendantService, CreateAttendantRequest, UpdateAttendantRequest}
import io.circe.generic.auto.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl

object AttendantRoutes:

  private case class CreateAttendantBody(name: String, phone: String)
  private case class UpdateAttendantBody(name: String, phone: String)
  private case class AssignAttendantsBody(attendantIds: List[String])

  private case class AttendantResponse(
    attendantId: String,
    providerId:  String,
    name:        String,
    phone:       String,
    isActive:    Boolean
  )

  private case class CreateAttendantResponse(attendantId: String)
  private case class ErrorResponseBody(error: String)

  private def toResponse(a: Attendant): AttendantResponse =
    AttendantResponse(a.id.value, a.providerId.value, a.name, a.phone, a.isActive)

  def routes[F[_]: Async](
    attendantService: AttendantService[F],
    jwtSecret:        String
  ): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*

    given EntityDecoder[F, CreateAttendantBody] = jsonOf[F, CreateAttendantBody]
    given EntityDecoder[F, UpdateAttendantBody] = jsonOf[F, UpdateAttendantBody]
    given EntityDecoder[F, AssignAttendantsBody] = jsonOf[F, AssignAttendantsBody]

    AuthMiddleware.withProviderId[F](jwtSecret) { rawProviderId =>

      val providerIdF: F[ProviderId] =
        ProviderId.fromString(rawProviderId)
          .fold(err => AttendantError.InvalidInput(err).raiseError[F, ProviderId], _.pure[F])

      HttpRoutes.of[F]:

        case GET -> Root / "dashboard" / "attendants" =>
          (for
            pid       <- providerIdF
            attendants <- attendantService.listActiveAttendants(pid)
            resp      <- Ok(attendants.map(toResponse).asJson)
          yield resp).handleErrorWith {
            case e: AttendantError => BadRequest(ErrorResponseBody(e.getMessage).asJson)
            case _                 => InternalServerError(ErrorResponseBody("Internal error").asJson)
          }

        case req @ POST -> Root / "dashboard" / "attendants" =>
          req.as[CreateAttendantBody].flatMap { body =>
            (for
              pid  <- providerIdF
              req2  = CreateAttendantRequest(pid, body.name, body.phone)
              id   <- attendantService.createAttendant(req2)
              resp <- Created(CreateAttendantResponse(id.value).asJson)
            yield resp).handleErrorWith {
              case e: AttendantError.InvalidInput => BadRequest(ErrorResponseBody(e.getMessage).asJson)
              case e: AttendantError              => BadRequest(ErrorResponseBody(e.getMessage).asJson)
            }
          }.handleErrorWith {
            case e: AttendantError.InvalidInput   => BadRequest(ErrorResponseBody(e.getMessage).asJson)
            case _: MalformedMessageBodyFailure   => BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
            case _: InvalidMessageBodyFailure     => BadRequest(ErrorResponseBody("Invalid request body").asJson)
          }

        case req @ PUT -> Root / "dashboard" / "attendants" / attendantIdStr =>
          req.as[UpdateAttendantBody].flatMap { body =>
            (for
              pid         <- providerIdF
              attendantId <- AttendantId.fromString(attendantIdStr)
                               .fold(err => AttendantError.InvalidInput(err).raiseError[F, AttendantId], _.pure[F])
              req2         = UpdateAttendantRequest(attendantId, pid, body.name, body.phone)
              _           <- attendantService.updateAttendant(req2)
              resp        <- Ok(().asJson)
            yield resp).handleErrorWith {
              case _: AttendantError.AttendantNotFound => NotFound(ErrorResponseBody("Attendant not found").asJson)
              case _: AttendantError.Forbidden         => Forbidden(ErrorResponseBody("Access denied").asJson)
              case e: AttendantError                   => BadRequest(ErrorResponseBody(e.getMessage).asJson)
              case _: MalformedMessageBodyFailure      => BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
              case _: InvalidMessageBodyFailure        => BadRequest(ErrorResponseBody("Invalid request body").asJson)
            }
          }.handleErrorWith {
            case _: MalformedMessageBodyFailure => BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
            case _: InvalidMessageBodyFailure   => BadRequest(ErrorResponseBody("Invalid request body").asJson)
          }

        case DELETE -> Root / "dashboard" / "attendants" / attendantIdStr =>
          (for
            pid         <- providerIdF
            attendantId <- AttendantId.fromString(attendantIdStr)
                             .fold(err => AttendantError.InvalidInput(err).raiseError[F, AttendantId], _.pure[F])
            _           <- attendantService.deactivateAttendant(attendantId, pid)
            resp        <- Ok(().asJson)
          yield resp).handleErrorWith {
            case _: AttendantError.AttendantNotFound => NotFound(ErrorResponseBody("Attendant not found").asJson)
            case _: AttendantError.Forbidden         => Forbidden(ErrorResponseBody("Access denied").asJson)
            case e: AttendantError                   => BadRequest(ErrorResponseBody(e.getMessage).asJson)
          }

        case req @ PUT -> Root / "dashboard" / "bookings" / bookingIdStr / "attendants" =>
          req.as[AssignAttendantsBody].flatMap { body =>
            (for
              pid         <- providerIdF
              bookingId   <- BookingId.fromString(bookingIdStr)
                               .fold(err => AttendantError.InvalidInput(err).raiseError[F, BookingId], _.pure[F])
              attendantIds <- body.attendantIds.traverse { idStr =>
                                AttendantId.fromString(idStr)
                                  .fold(err => AttendantError.InvalidInput(err).raiseError[F, AttendantId], _.pure[F])
                              }
              req2         = AssignAttendantsRequest(bookingId, pid, attendantIds)
              _           <- attendantService.assignAttendants(req2)
              resp        <- Ok(().asJson)
            yield resp).handleErrorWith {
              case _: AttendantError.AttendantNotFound => NotFound(ErrorResponseBody("Attendant not found").asJson)
              case _: AttendantError.BookingNotFound   => NotFound(ErrorResponseBody("Booking not found").asJson)
              case _: AttendantError.Forbidden         => Forbidden(ErrorResponseBody("Access denied").asJson)
              case _: AttendantError.AttendantInactive => BadRequest(ErrorResponseBody("Attendant is inactive").asJson)
              case e: AttendantError                   => BadRequest(ErrorResponseBody(e.getMessage).asJson)
              case _: MalformedMessageBodyFailure      => BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
              case _: InvalidMessageBodyFailure        => BadRequest(ErrorResponseBody("Invalid request body").asJson)
            }
          }.handleErrorWith {
            case _: MalformedMessageBodyFailure => BadRequest(ErrorResponseBody("Invalid or incomplete request body").asJson)
            case _: InvalidMessageBodyFailure   => BadRequest(ErrorResponseBody("Invalid request body").asJson)
          }
    }
