package com.locarya.adapters.persistence

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.ProviderRepository
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.decode as circeDecoder
import io.circe.syntax.*
import java.util.UUID

class ProviderRepositoryLive[F[_]: Async] private (xa: Transactor[F])
    extends ProviderRepository[F]:

  private given Decoder[StoreConfig] = deriveDecoder
  private given Encoder[StoreConfig] = deriveEncoder

  private case class ProviderRow(
    id:             UUID,
    email:          String,
    cpf:            Option[String],
    cnpj:           Option[String],
    businessName:   String,
    tradeName:      String,
    city:           String,
    state:          String,
    passwordHash:   String,
    plan:           String,
    storefrontSlug: String,
    isActive:       Boolean,
    storeConfig:    Option[String],
    walletId:       Option[String]
  ) derives Read

  private def rowToProvider(row: ProviderRow): F[Provider] =
    val storeConfig = row.storeConfig
      .flatMap(json => circeDecoder[StoreConfig](json).toOption)
      .getOrElse(StoreConfig())
    val result = for
      id      <- ProviderId.fromString(row.id.toString)
      email   <- Email.fromString(row.email)
      taxId   <- (row.cpf, row.cnpj) match
                   case (Some(c), None) => CPF.fromString(c).map(TaxId.fromCPF)
                   case (None, Some(c)) => CNPJ.fromString(c).map(TaxId.fromCNPJ)
                   case _               => Left(InvalidTaxId("Invalid tax ID state in DB"))
      planTier = if row.plan == "PREMIUM" then PlanTier.Premium else PlanTier.Freemium
      slug    <- StorefrontSlug.fromString(row.storefrontSlug)
      p       <- Provider.create(
                   id             = id,
                   email          = email,
                   taxId          = taxId,
                   businessName   = row.businessName,
                   tradeName      = row.tradeName,
                   city           = row.city,
                   state          = row.state,
                   passwordHash   = row.passwordHash,
                   planTier       = planTier,
                   storefrontSlug = slug,
                   isActive       = row.isActive,
                   storeConfig    = storeConfig,
                   walletId       = row.walletId
                 )
    yield p
    result.fold(
      err => Async[F].raiseError(new RuntimeException(s"DB→domain mapping failed: $err")),
      _.pure[F]
    )

  private val selectBase = fr"""
    SELECT id, email, cpf, cnpj, business_name, trade_name,
           city, state, password_hash, plan, storefront_slug, is_active,
           store_config, wallet_id
    FROM providers
  """

  private def planStr(tier: PlanTier): String = tier match
    case PlanTier.Freemium => "FREEMIUM"
    case PlanTier.Premium  => "PREMIUM"

  private def taxIdPair(taxId: TaxId): (Option[String], Option[String]) = taxId match
    case TaxId.CPFTaxId(c)  => (Some(c.value), None)
    case TaxId.CNPJTaxId(c) => (None, Some(c.value))

  def create(provider: Provider): F[Provider] =
    val (cpf, cnpj) = taxIdPair(provider.taxId)
    val uuid        = UUID.fromString(provider.id.value)
    sql"""
      INSERT INTO providers
        (id, email, cpf, cnpj, business_name, trade_name,
         city, state, password_hash, plan, storefront_slug, is_active)
      VALUES
        ($uuid, ${provider.email.value}, $cpf, $cnpj,
         ${provider.businessName}, ${provider.tradeName},
         ${provider.city}, ${provider.state},
         ${provider.passwordHash}, ${planStr(provider.planTier)},
         ${provider.storefrontSlug.value}, ${provider.isActive})
    """.update.run.transact(xa).adaptError {
      case ex: org.postgresql.util.PSQLException if ex.getSQLState == "23505" =>
        val doc = provider.taxId match
          case TaxId.CPFTaxId(c)  => c.value
          case TaxId.CNPJTaxId(c) => c.value
        SignupError.DuplicateDocument(doc)
    } >> provider.pure[F]

  def findById(id: ProviderId): F[Option[Provider]] =
    val uuid = UUID.fromString(id.value)
    (selectBase ++ fr"WHERE id = $uuid")
      .query[ProviderRow]
      .option
      .transact(xa)
      .flatMap(_.traverse(rowToProvider))

  def update(provider: Provider): F[Provider] =
    val (cpf, cnpj) = taxIdPair(provider.taxId)
    val uuid        = UUID.fromString(provider.id.value)
    sql"""
      UPDATE providers SET
        email          = ${provider.email.value},
        cpf            = $cpf,
        cnpj           = $cnpj,
        business_name  = ${provider.businessName},
        trade_name     = ${provider.tradeName},
        city           = ${provider.city},
        state          = ${provider.state},
        password_hash  = ${provider.passwordHash},
        plan           = ${planStr(provider.planTier)},
        storefront_slug = ${provider.storefrontSlug.value},
        is_active      = ${provider.isActive},
        wallet_id      = ${provider.walletId},
        updated_at     = NOW()
      WHERE id = $uuid
    """.update.run.transact(xa) >> provider.pure[F]

  def findByEmail(email: Email): F[Option[Provider]] =
    (selectBase ++ fr"WHERE email = ${email.value}")
      .query[ProviderRow]
      .option
      .transact(xa)
      .flatMap(_.traverse(rowToProvider))

  def findBySlug(slug: StorefrontSlug): F[Option[Provider]] =
    (selectBase ++ fr"WHERE storefront_slug = ${slug.value}")
      .query[ProviderRow]
      .option
      .transact(xa)
      .flatMap(_.traverse(rowToProvider))

  def updateWalletId(id: ProviderId, walletId: String): F[Provider] =
    val uuid = UUID.fromString(id.value)
    sql"""
      UPDATE providers SET wallet_id = $walletId, updated_at = NOW()
      WHERE id = $uuid
    """.update.run.transact(xa) >> findById(id).flatMap {
      case Some(p) => p.pure[F]
      case None    => Async[F].raiseError(new RuntimeException(s"Provider ${id.value} not found after update"))
    }

  def updateStoreConfig(id: ProviderId, config: StoreConfig): F[Provider] =
    val uuid    = UUID.fromString(id.value)
    val jsonStr = config.asJson.noSpaces
    sql"""
      UPDATE providers SET store_config = $jsonStr::jsonb, updated_at = NOW()
      WHERE id = $uuid
    """.update.run.transact(xa) >> findById(id).flatMap {
      case Some(p) => p.pure[F]
      case None    => Async[F].raiseError(new RuntimeException(s"Provider ${id.value} not found after update"))
    }

object ProviderRepositoryLive:
  def make[F[_]: Async](xa: Transactor[F]): ProviderRepository[F] =
    new ProviderRepositoryLive[F](xa)
