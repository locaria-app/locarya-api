package com.locarya.infrastructure.repository

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.core.domain.*
import com.locarya.services.ProviderRepo
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import DoobieMeta.given

class ProviderRepoImpl[F[_]: Async](xa: Transactor[F]) extends ProviderRepo[F] {

  override def create(provider: Provider): F[Provider] = {
    val (cpfOpt, cnpjOpt) = provider.taxId match {
      case TaxId.CPFTaxId(cpf) => (Some(cpf), None)
      case TaxId.CNPJTaxId(cnpj) => (None, Some(cnpj))
    }

    sql"""
      INSERT INTO providers (id, email, cpf, cnpj, business_name, trade_name, city, state, contract_status)
      VALUES (${provider.id}, ${provider.email}, $cpfOpt, $cnpjOpt, ${provider.businessName}, ${provider.tradeName}, ${provider.city}, ${provider.state}, ${provider.contractStatus})
    """.update.run.transact(xa).as(provider)
  }

  override def findById(id: ProviderId): F[Option[Provider]] = {
    sql"""
      SELECT id, email, cpf, cnpj, business_name, trade_name, city, state, contract_status
      FROM providers
      WHERE id = $id
    """.query[(ProviderId, Email, Option[CPF], Option[CNPJ], String, String, String, String, ContractStatus)]
      .option
      .transact(xa)
      .map(_.map { case (id, email, cpfOpt, cnpjOpt, businessName, tradeName, city, state, contractStatus) =>
        val taxId = (cpfOpt, cnpjOpt) match {
          case (Some(cpf), None) => TaxId.fromCPF(cpf)
          case (None, Some(cnpj)) => TaxId.fromCNPJ(cnpj)
          case _ => throw new IllegalStateException("Provider must have exactly one of CPF or CNPJ")
        }
        Provider.create(id, email, taxId, businessName, tradeName, city, state, contractStatus)
          .fold(e => throw new Exception(e.toString), identity)
      })
  }

  override def findByEmail(email: Email): F[Option[Provider]] = {
    sql"""
      SELECT id, email, cpf, cnpj, business_name, trade_name, city, state, contract_status
      FROM providers
      WHERE email = $email
    """.query[(ProviderId, Email, Option[CPF], Option[CNPJ], String, String, String, String, ContractStatus)]
      .option
      .transact(xa)
      .map(_.map { case (id, email, cpfOpt, cnpjOpt, businessName, tradeName, city, state, contractStatus) =>
        val taxId = (cpfOpt, cnpjOpt) match {
          case (Some(cpf), None) => TaxId.fromCPF(cpf)
          case (None, Some(cnpj)) => TaxId.fromCNPJ(cnpj)
          case _ => throw new IllegalStateException("Provider must have exactly one of CPF or CNPJ")
        }
        Provider.create(id, email, taxId, businessName, tradeName, city, state, contractStatus)
          .fold(e => throw new Exception(e.toString), identity)
      })
  }

  override def update(provider: Provider): F[Provider] = {
    val (cpfOpt, cnpjOpt) = provider.taxId match {
      case TaxId.CPFTaxId(cpf) => (Some(cpf), None)
      case TaxId.CNPJTaxId(cnpj) => (None, Some(cnpj))
    }

    sql"""
      UPDATE providers
      SET email = ${provider.email},
          cpf = $cpfOpt,
          cnpj = $cnpjOpt,
          business_name = ${provider.businessName},
          trade_name = ${provider.tradeName},
          city = ${provider.city},
          state = ${provider.state},
          contract_status = ${provider.contractStatus},
          updated_at = NOW()
      WHERE id = ${provider.id}
    """.update.run.transact(xa).as(provider)
  }
}
