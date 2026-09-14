package fi.tukkateatteri

import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.ReservationSyncState
import fi.tukkateatteri.data.TicketSaleOrigin
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.local.PendingSheetChangeStatus
import fi.tukkateatteri.data.local.PendingSheetOperation
import fi.tukkateatteri.data.local.ReservationTypeConverters
import fi.tukkateatteri.data.local.SheetFieldKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReservationTypeConvertersTest {
    private val converters = ReservationTypeConverters()

    @Test
    fun enumConvertersRoundTripEverySupportedValue() {
        AdmissionType.entries.forEach { value ->
            assertEquals(value, converters.admissionTypeFromStorage(converters.admissionTypeToStorage(value)))
        }
        TicketSaleOrigin.entries.forEach { value ->
            assertEquals(value, converters.ticketSaleOriginFromStorage(converters.ticketSaleOriginToStorage(value)))
        }
        ReservationSyncState.entries.forEach { value ->
            assertEquals(value, converters.reservationSyncStateFromStorage(converters.reservationSyncStateToStorage(value)))
        }
        PendingSheetOperation.entries.forEach { value ->
            assertEquals(value, converters.pendingSheetOperationFromStorage(converters.pendingSheetOperationToStorage(value)))
        }
        PendingSheetChangeStatus.entries.forEach { value ->
            assertEquals(value, converters.pendingSheetChangeStatusFromStorage(converters.pendingSheetChangeStatusToStorage(value)))
        }
        SheetFieldKind.entries.forEach { value ->
            assertEquals(value, converters.sheetFieldKindFromStorage(converters.sheetFieldKindToStorage(value)))
        }
    }

    @Test
    fun missingAndUnknownEnumsUseSafeDefaults() {
        listOf(null, "", "UNKNOWN", "reservation").forEach { stored ->
            assertEquals(AdmissionType.RESERVATION, converters.admissionTypeFromStorage(stored))
            assertEquals(TicketSaleOrigin.MANUAL, converters.ticketSaleOriginFromStorage(stored))
            assertEquals(ReservationSyncState.SYNCED, converters.reservationSyncStateFromStorage(stored))
            assertEquals(PendingSheetOperation.UPSERT, converters.pendingSheetOperationFromStorage(stored))
            assertEquals(PendingSheetChangeStatus.PENDING, converters.pendingSheetChangeStatusFromStorage(stored))
            assertEquals(SheetFieldKind.IGNORE, converters.sheetFieldKindFromStorage(stored))
        }
    }

    @Test
    fun builtInTicketAndPaymentDefinitionsRoundTrip() {
        TicketType.entries.forEach { value ->
            assertEquals(value, converters.ticketTypeFromStorage(converters.ticketTypeToStorage(value)))
        }
        PaymentMethod.entries.forEach { value ->
            assertEquals(value, converters.paymentMethodFromStorage(converters.paymentMethodToStorage(value)))
        }
        assertNull(converters.paymentMethodFromStorage(null))
        assertNull(converters.paymentMethodToStorage(null))
    }

    @Test
    fun dynamicDefinitionsPreserveDelimitersUnicodePricesAndSortOrder() {
        val ticket = TicketType(
            name = "CUSTOM|2026:VIP",
            label = "Ensi-ilta | ystävät: 17,50 €",
            defaultPriceCents = 1_750,
            sortOrder = 41
        )
        val payment = PaymentMethod(
            name = "EDENRED|MOBILE:V2",
            label = "Edenred | mobiili: uusi",
            allowsSplitPayment = false,
            sortOrder = 23
        )

        assertEquals(ticket, converters.ticketTypeFromStorage(converters.ticketTypeToStorage(ticket)))
        assertEquals(payment, converters.paymentMethodFromStorage(converters.paymentMethodToStorage(payment)))
    }

    @Test
    fun malformedDynamicDefinitionsFailClosed() {
        val malformed = listOf(
            "v1|",
            "v1|x|1|4:NAMElabel",
            "v1|100|x|4:NAMElabel",
            "v1|100|1|999:short",
            "v1|100|1|-1:NAME"
        )

        malformed.forEach { value ->
            assertEquals(TicketType.UNSPECIFIED, converters.ticketTypeFromStorage(value))
            assertNull(converters.paymentMethodFromStorage(value))
        }
    }

    @Test
    fun unknownLegacyDefinitionsFailClosed() {
        assertEquals(TicketType.UNSPECIFIED, converters.ticketTypeFromStorage("OLD_TICKET"))
        assertNull(converters.paymentMethodFromStorage("OLD_PAYMENT"))
    }
}
