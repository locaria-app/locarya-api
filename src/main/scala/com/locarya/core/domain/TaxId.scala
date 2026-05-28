package com.locarya.core.domain

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

  def fromCPF(cpf: CPF): TaxId = CPFTaxId(cpf)

  def fromCNPJ(cnpj: CNPJ): TaxId = CNPJTaxId(cnpj)

  def fromString(value: String): Either[ValidationError, TaxId] = {
    // Try CPF first (11 digits)
    CPF.fromString(value) match {
      case Right(cpf) => Right(fromCPF(cpf))
      case Left(_) =>
        // Try CNPJ (14 digits)
        CNPJ.fromString(value) match {
          case Right(cnpj) => Right(fromCNPJ(cnpj))
          case Left(_) =>
            // Neither worked
            Left(InvalidTaxId(s"Value is neither a valid CPF nor a valid CNPJ: $value"))
        }
    }
  }
}
