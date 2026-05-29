package com.locarya.core.domain

import munit.FunSuite
import java.time.LocalDate

class BookingSpec extends FunSuite {

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
      totalAmount = totalAmount
    )

    assert(result.isRight, "Should create Booking with valid date range")
    result.foreach { booking =>
      assertEquals(booking.id, bookingId)
      assertEquals(booking.startDate, startDate)
      assertEquals(booking.endDate, endDate)
      assertEquals(booking.status, BookingStatus.Pending)
      assertEquals(booking.attendantId, None)
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
      totalAmount = totalAmount
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
      totalAmount = totalAmount
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
      totalAmount = totalAmount
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
      totalAmount = totalAmount
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
      totalAmount = totalAmount
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
      totalAmount = totalAmount
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
      totalAmount = totalAmount
    ).toOption.get

    val result = booking.transitionStatus(BookingStatus.Confirmed)

    assert(result.isRight, "Should transition from Pending to Confirmed")
    result.foreach { updatedBooking =>
      assertEquals(updatedBooking.status, BookingStatus.Confirmed)
    }
  }

  test("assign attendant to Booking succeeds") {
    val bookingId = BookingId.generate
    val providerId = ProviderId.generate
    val customerId = CustomerId.generate
    val itemId = ItemId.generate
    val attendantId = AttendantId.generate
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
      totalAmount = totalAmount
    ).toOption.get

    val updatedBooking = booking.assignAttendant(attendantId)

    assertEquals(updatedBooking.attendantId, Some(attendantId))
  }
}
