package com.locarya.domain.services

import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import org.mindrot.jbcrypt.BCrypt
import org.typelevel.log4cats.Logger

class ProviderServiceImpl[F[_]: Sync: Logger](
  repo: ProviderRepository[F]
) extends ProviderService[F]:

  private val MinPasswordLength = 8

  def signup(request: SignupRequest): F[SignupResult] =
    for
      validEmail <- lift(Email.fromString(request.email))
      _          <- requireMinLength(request.password, MinPasswordLength, "Password")
      _          <- requireNonEmpty(request.name, "Name")
      _          <- requireNonEmpty(request.city, "City")
      _          <- requireNonEmpty(request.state, "State")
      validTaxId <- lift(buildTaxId(request.cpf, request.cnpj))
      _          <- checkEmailAvailable(validEmail)
      hash       <- Sync[F].blocking(BCrypt.hashpw(request.password, BCrypt.gensalt()))
      slug       <- uniqueSlug(request.name.trim)
      provider   <- lift(
                      Provider.create(
                        id             = ProviderId.generate,
                        email          = validEmail,
                        taxId          = validTaxId,
                        businessName   = request.name.trim,
                        tradeName      = request.name.trim,
                        city           = request.city.trim,
                        state          = request.state.trim,
                        passwordHash   = hash,
                        planTier       = PlanTier.Freemium,
                        storefrontSlug = slug
                      )
                    )
      stored     <- repo.create(provider)
      _          <- Logger[F].info(
                      s"""{"event":"ProviderCreated","providerId":"${stored.id.value}","slug":"${stored.storefrontSlug.value}"}"""
                    )
    yield SignupResult(stored.id, stored.storefrontSlug)

  private def lift[A](e: Either[ValidationError, A]): F[A] =
    e.fold(err => SignupError.InvalidInput(err).raiseError[F, A], _.pure[F])

  private def requireNonEmpty(value: String, field: String): F[Unit] =
    if value.trim.isEmpty then
      SignupError.InvalidInput(InvalidProvider(s"$field cannot be empty")).raiseError
    else ().pure[F]

  private def requireMinLength(value: String, min: Int, field: String): F[Unit] =
    if value.length < min then
      SignupError.InvalidInput(InvalidPassword(s"$field must be at least $min characters")).raiseError
    else ().pure[F]

  private def buildTaxId(
    cpf:  Option[String],
    cnpj: Option[String]
  ): Either[ValidationError, TaxId] =
    (cpf, cnpj) match
      case (Some(c), None) => CPF.fromString(c).map(TaxId.fromCPF)
      case (None, Some(c)) => CNPJ.fromString(c).map(TaxId.fromCNPJ)
      case (Some(_), Some(_)) =>
        Left(InvalidTaxId("Provider cannot have both CPF and CNPJ"))
      case (None, None) =>
        Left(InvalidTaxId("Provider must have either CPF or CNPJ"))

  private def checkEmailAvailable(email: Email): F[Unit] =
    repo.findByEmail(email).flatMap {
      case Some(_) => SignupError.DuplicateEmail(email.value).raiseError
      case None    => ().pure[F]
    }

  def findById(id: ProviderId): F[Option[Provider]] =
    repo.findById(id)

  def updateStoreConfig(providerId: ProviderId, config: StoreConfig): F[Provider] =
    repo.findById(providerId).flatMap {
      case None    => ProviderError.NotFound(providerId).raiseError[F, Provider]
      case Some(_) => repo.updateStoreConfig(providerId, config)
    }

  private def uniqueSlug(tradeName: String, attempts: Int = 5): F[StorefrontSlug] =
    if attempts <= 0 then
      new RuntimeException("Failed to generate unique slug after retries").raiseError
    else
      val slug = StorefrontSlug.generate(tradeName)
      repo.findBySlug(slug).flatMap {
        case Some(_) => uniqueSlug(tradeName, attempts - 1)
        case None    => slug.pure[F]
      }
