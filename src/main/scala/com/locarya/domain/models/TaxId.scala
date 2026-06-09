package com.locarya.domain.models

sealed trait TaxId {
  def isCPF: Boolean
  def isCNPJ: Boolean
  def value: String
}

object TaxId {
  final case class CPFTaxId(cpf: CPF) extends TaxId {
    override def isCPF: Boolean = true
    override def isCNPJ: Boolean = false
    override def value: String = cpf.value
  }

  final case class CNPJTaxId(cnpj: CNPJ) extends TaxId {
    override def isCPF: Boolean = false
    override def isCNPJ: Boolean = true
    override def value: String = cnpj.value
  }

  def create(cpfOpt: Option[CPF], cnpjOpt: Option[CNPJ]): Either[ValidationError, TaxId] = {
    (cpfOpt, cnpjOpt) match {
      case (Some(cpf), None) => Right(CPFTaxId(cpf))
      case (None, Some(cnpj)) => Right(CNPJTaxId(cnpj))
      case (Some(_), Some(_)) => Left(InvalidTaxId("Provider cannot have both CPF and CNPJ"))
      case (None, None) => Left(InvalidTaxId("Provider must have either CPF or CNPJ"))
    }
  }

  def fromCPF(cpf: CPF): TaxId = CPFTaxId(cpf)

  def fromCNPJ(cnpj: CNPJ): TaxId = CNPJTaxId(cnpj)
}
