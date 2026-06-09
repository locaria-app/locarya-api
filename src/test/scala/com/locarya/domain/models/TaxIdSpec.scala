package com.locarya.domain.models

import munit.FunSuite

class TaxIdSpec extends FunSuite {

  test("create TaxId with CPF and no CNPJ succeeds") {
    val cpf = CPF.fromString("123.456.789-09").toOption.get
    val result = TaxId.create(Some(cpf), None)

    assert(result.isRight, "Should succeed with valid CPF and no CNPJ")
    result.foreach { taxId =>
      assert(taxId.isCPF, "TaxId should be a CPF")
      assert(!taxId.isCNPJ, "TaxId should not be a CNPJ")
    }
  }

  test("create TaxId with CNPJ and no CPF succeeds") {
    val cnpj = CNPJ.fromString("11.222.333/0001-81").toOption.get
    val result = TaxId.create(None, Some(cnpj))

    assert(result.isRight, "Should succeed with valid CNPJ and no CPF")
    result.foreach { taxId =>
      assert(taxId.isCNPJ, "TaxId should be a CNPJ")
      assert(!taxId.isCPF, "TaxId should not be a CPF")
    }
  }

  test("create TaxId with both CPF and CNPJ fails") {
    val cpf = CPF.fromString("123.456.789-09").toOption.get
    val cnpj = CNPJ.fromString("11.222.333/0001-81").toOption.get
    val result = TaxId.create(Some(cpf), Some(cnpj))

    assert(result.isLeft, "Should fail when both CPF and CNPJ are provided")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidTaxId], "Should return InvalidTaxId error")
    }
  }

  test("create TaxId with neither CPF nor CNPJ fails") {
    val result = TaxId.create(None, None)

    assert(result.isLeft, "Should fail when neither CPF nor CNPJ are provided")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidTaxId], "Should return InvalidTaxId error")
    }
  }

  test("create TaxId from valid CPF succeeds") {
    val cpf = CPF.fromString("123.456.789-09")

    cpf.foreach { validCpf =>
      val taxId = TaxId.fromCPF(validCpf)

      assert(taxId.isCPF, "TaxId should be a CPF")
      assert(!taxId.isCNPJ, "TaxId should not be a CNPJ")
    }
  }

  test("create TaxId from valid CNPJ succeeds") {
    val cnpj = CNPJ.fromString("11.222.333/0001-81")

    cnpj.foreach { validCnpj =>
      val taxId = TaxId.fromCNPJ(validCnpj)

      assert(taxId.isCNPJ, "TaxId should be a CNPJ")
      assert(!taxId.isCPF, "TaxId should not be a CPF")
    }
  }

  test("TaxId can be pattern matched to extract CPF") {
    val cpf = CPF.fromString("123.456.789-09").toOption.get
    val taxId = TaxId.fromCPF(cpf)

    taxId match {
      case TaxId.CPFTaxId(extractedCpf) =>
        assertEquals(extractedCpf, cpf, "Should extract the same CPF")
      case _ =>
        fail("Should match CPFTaxId")
    }
  }

  test("TaxId can be pattern matched to extract CNPJ") {
    val cnpj = CNPJ.fromString("11.222.333/0001-81").toOption.get
    val taxId = TaxId.fromCNPJ(cnpj)

    taxId match {
      case TaxId.CNPJTaxId(extractedCnpj) =>
        assertEquals(extractedCnpj, cnpj, "Should extract the same CNPJ")
      case _ =>
        fail("Should match CNPJTaxId")
    }
  }

  test("TaxId enforces exactly one tax ID type - CPF case") {
    val cpf = CPF.fromString("123.456.789-09").toOption.get
    val taxId = TaxId.fromCPF(cpf)

    // Should not be both
    assert(taxId.isCPF && !taxId.isCNPJ, "Should be CPF and not CNPJ")

    // Pattern matching should only match one case
    var matchCount = 0
    taxId match {
      case TaxId.CPFTaxId(_) => matchCount += 1
      case TaxId.CNPJTaxId(_) => matchCount += 1
    }
    assertEquals(matchCount, 1, "Should match exactly one pattern")
  }

  test("TaxId enforces exactly one tax ID type - CNPJ case") {
    val cnpj = CNPJ.fromString("11.222.333/0001-81").toOption.get
    val taxId = TaxId.fromCNPJ(cnpj)

    // Should not be both
    assert(taxId.isCNPJ && !taxId.isCPF, "Should be CNPJ and not CPF")

    // Pattern matching should only match one case
    var matchCount = 0
    taxId match {
      case TaxId.CPFTaxId(_) => matchCount += 1
      case TaxId.CNPJTaxId(_) => matchCount += 1
    }
    assertEquals(matchCount, 1, "Should match exactly one pattern")
  }

  test("TaxId provides access to raw value") {
    val cpf = CPF.fromString("123.456.789-09").toOption.get
    val cpfTaxId = TaxId.fromCPF(cpf)
    assertEquals(cpfTaxId.value, "12345678909", "Should return normalized CPF value")

    val cnpj = CNPJ.fromString("11.222.333/0001-81").toOption.get
    val cnpjTaxId = TaxId.fromCNPJ(cnpj)
    assertEquals(cnpjTaxId.value, "11222333000181", "Should return normalized CNPJ value")
  }
}
