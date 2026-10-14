package pl.flipbot.playwright.negotiation;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import org.junit.Test;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class FirstOfferPostSubmitObservationTest {
    private final FirstOfferPostSubmitObservation observation =
            new FirstOfferPostSubmitObservation();

    @Test
    public void visibleMatchingOwnOfferAndStatusAreObservedWithoutAnySubmit() {
        Page page = mock(Page.class);
        Locator prices = mock(Locator.class), offer = mock(Locator.class);
        Locator statuses = mock(Locator.class), status = mock(Locator.class);
        when(page.getByTestId(NegotiationSelectors.OWN_OFFER_PRICE)).thenReturn(prices);
        when(prices.last()).thenReturn(offer);
        when(offer.innerText()).thenReturn("1 200,00 zł");
        when(page.getByTestId(NegotiationSelectors.OWN_OFFER_STATUS)).thenReturn(statuses);
        when(statuses.last()).thenReturn(status);
        when(status.innerText()).thenReturn("Oczekująca");

        observation.verifySubmittedOfferSafely(page, listing(), new BigDecimal("1200.00"));

        verify(offer).waitFor(any(Locator.WaitForOptions.class));
        verify(status).waitFor(any(Locator.WaitForOptions.class));
        verify(page, never()).getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON);
    }

    @Test
    public void mismatchingDisplayedPriceCannotCauseDuplicateSend() {
        Page page = mock(Page.class);
        Locator prices = mock(Locator.class), offer = mock(Locator.class);
        Locator statuses = mock(Locator.class), status = mock(Locator.class);
        when(page.getByTestId(NegotiationSelectors.OWN_OFFER_PRICE)).thenReturn(prices);
        when(prices.last()).thenReturn(offer);
        when(offer.innerText()).thenReturn("1 198,00 zł");
        when(page.getByTestId(NegotiationSelectors.OWN_OFFER_STATUS)).thenReturn(statuses);
        when(statuses.last()).thenReturn(status);

        observation.verifySubmittedOfferSafely(page, listing(), new BigDecimal("1200.00"));

        verify(page, never()).getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON);
    }

    @Test
    public void detachedOrUnreadablePriceIsOnlyDiagnosticAfterBackendUpdate() {
        Page page = mock(Page.class);
        Locator prices = mock(Locator.class), offer = mock(Locator.class);
        when(page.getByTestId(NegotiationSelectors.OWN_OFFER_PRICE)).thenReturn(prices);
        when(prices.last()).thenReturn(offer);
        when(offer.innerText()).thenThrow(new IllegalStateException("DOM changed"));

        observation.verifySubmittedOfferSafely(page, listing(), new BigDecimal("1200.00"));

        verify(page, never()).getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON);
        verify(page, never()).getByTestId(NegotiationSelectors.MESSAGE_SEND_ICON);
    }

    @Test
    public void missingStatusTimeoutRemainsNonFatalAndNeverResendsPrice() {
        Page page = mock(Page.class);
        Locator prices = mock(Locator.class), offer = mock(Locator.class);
        Locator statuses = mock(Locator.class), status = mock(Locator.class);
        when(page.getByTestId(NegotiationSelectors.OWN_OFFER_PRICE)).thenReturn(prices);
        when(prices.last()).thenReturn(offer);
        when(offer.innerText()).thenReturn("1 200,00 zł");
        when(page.getByTestId(NegotiationSelectors.OWN_OFFER_STATUS)).thenReturn(statuses);
        when(statuses.last()).thenReturn(status);
        doThrow(new TimeoutError("Status not shown"))
                .when(status).waitFor(any(Locator.WaitForOptions.class));

        observation.verifySubmittedOfferSafely(page, listing(), new BigDecimal("1200.00"));

        verify(page, never()).getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON);
    }

    private static ListingResponseDto listing() {
        ListingResponseDto listing = mock(ListingResponseDto.class);
        when(listing.listingId()).thenReturn("123");
        when(listing.id()).thenReturn(42L);
        return listing;
    }
}
