package com.locarya.domain.models

import munit.FunSuite
import java.time.{Instant, LocalDate}

class BookingSpec extends FunSuite {

  private val testCreatedAt = Instant.parse("2026-06-01T10:00:00Z")

  test("create Booking preserves createdAt") {
    val bookingId  = BookingId.generate
    val providerId = ProviderId.generate
    val customerId = CustomerId.generate
    val itemId     = ItemId.generate
    val total      = Money.fromAmount(BigDecimal("100.00")).toOption.get
    val date       = LocalDate.of(2026, 6, 1)
    val instant    = Instant.parse("2026-03-15T08:30:00Z")

    val result = Booking.create(
      id          = bookingId,
      providerId  = providerId,
      customerId  = customerId,
      items       = List(BookedIndividualItem(itemId, 1)),
      startDate   = date,
      endDate     = date,
      totalAmount = total,
      createdAt   = instant
    )

    assert(result.isRight)
    result.foreach { booking =>
      assertEquals(booking.createdAt, instant)
    }
  }

  test("create Booking with valid date range succeeds") {
    val bookingId = BookingId.generate
    val providerId = ProviderId.generate
    val customerId = CustomerId.generate
    val itemId = ItemId.generate
    val totalAmount = Money.fromAmount(BigDecimal("300.00")).toOption.get
    val startDate = LocalDate.of(2026, 6, 1)
    val endDate = LocalDate.of(2026, 6, 5)

    val result = Booking.create(
      id = bookingId,
      providerId = providerId,
      customerId = customerId,
      items = List(BookedIndividualItem(itemId, 2)),
      startDate = startDate,
      endDate = endDate,
      totalAmount = totalAmount,
      createdAt = testCreatedAt
    )

    assert(result.isRight, "Should create Booking with valid date range")
    result.foreach { booking =>
      assertEquals(booking.id, bookingId)
      assertEquals(booking.startDate, startDate)
      assertEquals(booking.endDate, endDate)
      assertEquals(booking.status, BookingStatus.Pending)
    }
  }

  test("create Booking with same-day rental succeeds") {
    val bookingId = BookingId.generate
    val providerId = ProviderId.generate
    val customerId = CustomerId.generate
    val itemId = ItemId.generate
    val totalAmount = Money.fromAmount(BigDecimal("100.00")).toOption.get
    val sameDate = LocalDate.of(2026, 6, 1)

    val result = Booking.create(
      id = bookingId,
      providerId = providerId,
      customerId = customerId,
      items = List(BookedIndividualItem(itemId, 1)),
      startDate = sameDate,
      endDate = sameDate,
      totalAmount = totalAmount,
      createdAt = testCreatedAt
    )

    assert(result.isRight, "Should create Booking with same-day rental (startDate == endDate)")
    result.foreach { booking =>
      assertEquals(booking.startDate, sameDate)
      assertEquals(booking.endDate, sameDate)
    }
  }

  test("create Booking with end date before start date fails") {
    val bookingId = BookingId.generate
    val providerId = ProviderId.generate
    val customerId = CustomerId.generate
    val itemId = ItemId.generate
    val totalAmount = Money.fromAmount(BigDecimal("100.00")).toOption.get
    val startDate = LocalDate.of(2026, 6, 5)
    val endDate = LocalDate.of(2026, 6, 1)

    val result = Booking.create(
      id = bookingId,
      providerId = providerId,
      customerId = customerId,
      items = List(BookedIndividualItem(itemId, 1)),
      startDate = startDate,
      endDate = endDate,
      totalAmount = totalAmount,
      createdAt = testCreatedAt
    )

    assert(result.isLeft, "Should fail when end date is before start date")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidBooking], "Should return InvalidBooking error")
    }
  }

  test("create Booking with empty items list fails") {
    val bookingId = BookingId.generate
    val providerId = ProviderId.generate
    val customerId = CustomerId.generate
    val totalAmount = Money.fromAmount(BigDecimal("100.00")).toOption.get
    val startDate = LocalDate.of(2026, 6, 1)
    val endDate = LocalDate.of(2026, 6, 5)

    val result = Booking.create(
      id = bookingId,
      providerId = providerId,
      customerId = customerId,
      items = List.empty,
      startDate = startDate,
      endDate = endDate,
      totalAmount = totalAmount,
      createdAt = testCreatedAt
    )

    assert(result.isLeft, "Should fail with empty items list")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidBooking], "Should return InvalidBooking error")
    }
  }

  test("create Booking with zero quantity individual item fails") {
    val bookingId = BookingId.generate
    val providerId = ProviderId.generate
    val customerId = CustomerId.generate
    val itemId = ItemId.generate
    val totalAmount = Money.fromAmount(BigDecimal("100.00")).toOption.get
    val startDate = LocalDate.of(2026, 6, 1)
    val endDate = LocalDate.of(2026, 6, 5)

    val result = Booking.create(
      id = bookingId,
      providerId = providerId,
      customerId = customerId,
      items = List(BookedIndividualItem(itemId, 0)),
      startDate = startDate,
      endDate = endDate,
      totalAmount = totalAmount,
      createdAt = testCreatedAt
    )

    assert(result.isLeft, "Should fail with zero quantity item")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidBooking], "Should return InvalidBooking error")
    }
  }

  test("create Booking with negative quantity combo fails") {
    val bookingId = BookingId.generate
    val providerId = ProviderId.generate
    val customerId = CustomerId.generate
    val comboId = ComboId.generate
    val totalAmount = Money.fromAmount(BigDecimal("100.00")).toOption.get
    val startDate = LocalDate.of(2026, 6, 1)
    val endDate = LocalDate.of(2026, 6, 5)

    val result = Booking.create(
      id = bookingId,
      providerId = providerId,
      customerId = customerId,
      items = List(BookedCombo(comboId, -1)),
      startDate = startDate,
      endDate = endDate,
      totalAmount = totalAmount,
      createdAt = testCreatedAt
    )

    assert(result.isLeft, "Should fail with negative quantity combo")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidBooking], "Should return InvalidBooking error")
    }
  }

  test("create Booking with mixed items (individual and combo) succeeds") {
    val bookingId = BookingId.generate
    val providerId = ProviderId.generate
    val customerId = CustomerId.generate
    val itemId = ItemId.generate
    val comboId = ComboId.generate
    val totalAmount = Money.fromAmount(BigDecimal("500.00")).toOption.get
    val startDate = LocalDate.of(2026, 6, 1)
    val endDate = LocalDate.of(2026, 6, 5)

    val result = Booking.create(
      id = bookingId,
      providerId = providerId,
      customerId = customerId,
      items = List(
        BookedIndividualItem(itemId, 2),
        BookedCombo(comboId, 1)
      ),
      startDate = startDate,
      endDate = endDate,
      totalAmount = totalAmount,
      createdAt = testCreatedAt
    )

    assert(result.isRight, "Should create Booking with mixed items")
    result.foreach { booking =>
      assertEquals(booking.items.size, 2)
    }
  }

  test("transition Booking from Pending to Confirmed succeeds") {
    val bookingId = BookingId.generate
    val providerId = ProviderId.generate
    val customerId = CustomerId.generate
    val itemId = ItemId.generate
    val totalAmount = Money.fromAmount(BigDecimal("100.00")).toOption.get
    val startDate = LocalDate.of(2026, 6, 1)
    val endDate = LocalDate.of(2026, 6, 5)

    val booking = Booking.create(
      id = bookingId,
      providerId = providerId,
      customerId = customerId,
      items = List(BookedIndividualItem(itemId, 1)),
      startDate = startDate,
      endDate = endDate,
      totalAmount = totalAmount,
      createdAt = testCreatedAt
    ).toOption.get

    val result = booking.transitionStatus(BookingStatus.Confirmed)

    assert(result.isRight, "Should transition from Pending to Confirmed")
    result.foreach { updatedBooking =>
      assertEquals(updatedBooking.status, BookingStatus.Confirmed)
    }
  }

  test("create Booking with delivery address for delivery succeeds") {
    val bookingId = BookingId.generate
    val providerId = ProviderId.generate
    val customerId = CustomerId.generate
    val itemId = ItemId.generate
    val totalAmount = Money.fromAmount(BigDecimal("200.00")).toOption.get
    val startDate = LocalDate.of(2026, 6, 1)
    val endDate = LocalDate.of(2026, 6, 5)

    val deliveryAddress = Address.create(
      street = "Rua das Flores",
      number = "123",
      neighborhood = "Centro",
      city = "São Paulo",
      state = "SP",
      cep = "01310-100",
      complement = Some("Apto 45")
    ).toOption.get

    val result = Booking.create(
      id = bookingId,
      providerId = providerId,
      customerId = customerId,
      items = List(BookedIndividualItem(itemId, 2)),
      startDate = startDate,
      endDate = endDate,
      totalAmount = totalAmount,
      deliveryAddress = Some(deliveryAddress),
      createdAt = testCreatedAt
    )

    assert(result.isRight, "Should create Booking with delivery address")
    result.foreach { booking =>
      assertEquals(booking.deliveryAddress, Some(deliveryAddress))
    }
  }

  test("create Booking without delivery address for pickup succeeds") {
    val bookingId = BookingId.generate
    val providerId = ProviderId.generate
    val customerId = CustomerId.generate
    val itemId = ItemId.generate
    val totalAmount = Money.fromAmount(BigDecimal("150.00")).toOption.get
    val startDate = LocalDate.of(2026, 6, 1)
    val endDate = LocalDate.of(2026, 6, 5)

    val result = Booking.create(
      id = bookingId,
      providerId = providerId,
      customerId = customerId,
      items = List(BookedIndividualItem(itemId, 1)),
      startDate = startDate,
      endDate = endDate,
      totalAmount = totalAmount,
      createdAt = testCreatedAt
    )

    assert(result.isRight, "Should create Booking without delivery address for pickup")
    result.foreach { booking =>
      assertEquals(booking.deliveryAddress, None)
    }
  }

  test("create Booking with partyProfile sets the field") {
    val bookingId    = BookingId.generate
    val providerId   = ProviderId.generate
    val customerId   = CustomerId.generate
    val itemId       = ItemId.generate
    val totalAmount  = Money.fromAmount(BigDecimal("300.00")).toOption.get
    val startDate    = LocalDate.of(2026, 6, 1)
    val endDate      = LocalDate.of(2026, 6, 5)
    val partyProfile = PartyProfile(Some(10), Some(List("toddler", "kids")), Some("outdoor"))

    val result = Booking.create(
      id           = bookingId,
      providerId   = providerId,
      customerId   = customerId,
      items        = List(BookedIndividualItem(itemId, 2)),
      startDate    = startDate,
      endDate      = endDate,
      totalAmount  = totalAmount,
      partyProfile = Some(partyProfile),
      createdAt    = testCreatedAt
    )

    assert(result.isRight, "Should create Booking with partyProfile")
    result.foreach { booking =>
      assertEquals(booking.partyProfile, Some(partyProfile))
    }
  }

  test("create Booking without partyProfile defaults to None") {
    val bookingId   = BookingId.generate
    val providerId  = ProviderId.generate
    val customerId  = CustomerId.generate
    val itemId      = ItemId.generate
    val totalAmount = Money.fromAmount(BigDecimal("100.00")).toOption.get
    val startDate   = LocalDate.of(2026, 6, 1)
    val endDate     = LocalDate.of(2026, 6, 5)

    val result = Booking.create(
      id          = bookingId,
      providerId  = providerId,
      customerId  = customerId,
      items       = List(BookedIndividualItem(itemId, 1)),
      startDate   = startDate,
      endDate     = endDate,
      totalAmount = totalAmount,
      createdAt   = testCreatedAt
    )

    assert(result.isRight)
    result.foreach { booking =>
      assertEquals(booking.partyProfile, None)
    }
  }
}
