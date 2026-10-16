package pl.flipbot.playwright.negotiation;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.Test;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;

import java.math.BigDecimal;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class FirstOfferPreparedFormGuardTest {
    private final FirstOfferListingReadiness readiness = mock(FirstOfferListingReadiness.class);
    private final FirstOfferPreparedFormGuard guard = new FirstOfferPreparedFormGuard(readiness);
    private final BigDecimal expected = new BigDecimal("1200.00");

    @Test
    public void wrongListingUrlPreventsQuotaReadyResultWithoutFormInteraction() {
        Page page = mock(Page.class);
        ListingResponseDto listing = listing();
        when(page.url()).thenReturn("https://www.vinted.pl/items/999-other");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> guard.assertReady(page, listing, expected))
                .getMessage().contains("page changed before quota reservation"));
        verify(page, never()).getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON);
    }

    @Test
    public void missingModalIsRejectedBeforeAnySubmitCheck() {
        Page page = mock(Page.class);
        Locator inputs = mock(Locator.class), input = mock(Locator.class);
        when(readiness.isCurrentListingPage(page, "123")).thenReturn(true);
        when(page.getByTestId(NegotiationSelectors.OFFER_PRICE_INPUT)).thenReturn(inputs);
        when(inputs.first()).thenReturn(input);
        when(input.isVisible()).thenReturn(false);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> guard.assertReady(page, listing(), expected))
                .getMessage().contains("form disappeared"));
        verify(page, never()).getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON);
    }

    @Test
    public void changedPriceIsRejectedBeforeLookingUpSubmitButton() {
        Page page = mock(Page.class);
        Locator inputs = mock(Locator.class), input = mock(Locator.class);
        when(readiness.isCurrentListingPage(page, "123")).thenReturn(true);
        when(page.getByTestId(NegotiationSelectors.OFFER_PRICE_INPUT)).thenReturn(inputs);
        when(inputs.first()).thenReturn(input);
        when(input.isVisible()).thenReturn(true);
        when(input.inputValue()).thenReturn("1201.00");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> guard.assertReady(page, listing(), expected))
                .getMessage().contains("price changed before quota reservation"));
        verify(page, never()).getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON);
    }

    @Test
    public void disabledSubmitButtonFailsClosedWithoutClicking() {
        Page page = mock(Page.class);
        Locator submit = prepareVisibleForm(page, false);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> guard.assertReady(page, listing(), expected))
                .getMessage().contains("disabled before quota reservation"));
        verify(submit).waitFor(any(Locator.WaitForOptions.class));
        verify(submit, never()).click(any(Locator.ClickOptions.class));
    }

    @Test
    public void allPreparedEvidencePresentPassesWithoutSendingOffer() {
        Page page = mock(Page.class);
        Locator submit = prepareVisibleForm(page, true);
        guard.assertReady(page, listing(), expected);
        verify(submit).waitFor(any(Locator.WaitForOptions.class));
        verify(submit).isEnabled();
        verify(submit, never()).click(any(Locator.ClickOptions.class));
    }

    private Locator prepareVisibleForm(Page page, boolean enabled) {
        Locator inputs = mock(Locator.class), input = mock(Locator.class);
        Locator buttons = mock(Locator.class), submit = mock(Locator.class);
        when(readiness.isCurrentListingPage(page, "123")).thenReturn(true);
        when(page.getByTestId(NegotiationSelectors.OFFER_PRICE_INPUT)).thenReturn(inputs);
        when(inputs.first()).thenReturn(input);
        when(input.isVisible()).thenReturn(true);
        when(input.inputValue()).thenReturn("1200.00");
        when(page.getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON)).thenReturn(buttons);
        when(buttons.first()).thenReturn(submit);
        when(submit.isEnabled()).thenReturn(enabled);
        return submit;
    }

    private static ListingResponseDto listing() {
        ListingResponseDto l = mock(ListingResponseDto.class);
        when(l.listingId()).thenReturn("123");
        return l;
    }
}
