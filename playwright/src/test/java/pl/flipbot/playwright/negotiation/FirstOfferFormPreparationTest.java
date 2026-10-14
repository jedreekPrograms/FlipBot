package pl.flipbot.playwright.negotiation;

import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import org.junit.Test;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class FirstOfferFormPreparationTest {
    private final HumanVerificationHandler verification = mock(HumanVerificationHandler.class);
    private final FirstOfferFormPreparation form = new FirstOfferFormPreparation(verification);

    @Test
    public void alreadyVisibleModalRefusesOpeningAndNeverClicksButton() {
        Page page = mock(Page.class);
        Locator button = mock(Locator.class), inputs = mock(Locator.class), input = mock(Locator.class);
        when(page.getByTestId(NegotiationSelectors.OFFER_PRICE_INPUT)).thenReturn(inputs);
        when(inputs.first()).thenReturn(input);
        when(input.isVisible()).thenReturn(true);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> form.openOfferModal(page, listing(), button))
                .getMessage().contains("already visible"));
        verify(button, never()).click(any(Locator.ClickOptions.class));
        verify(page, never()).getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON);
    }

    @Test
    public void disabledOfferButtonStopsBeforeOpeningModal() {
        Page page = mock(Page.class);
        Locator button = mock(Locator.class), inputs = mock(Locator.class), input = mock(Locator.class);
        when(page.getByTestId(NegotiationSelectors.OFFER_PRICE_INPUT)).thenReturn(inputs);
        when(inputs.first()).thenReturn(input);
        when(input.isVisible()).thenReturn(false);
        when(button.isEnabled()).thenReturn(false);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> form.openOfferModal(page, listing(), button))
                .getMessage().contains("disabled"));
        verify(button, never()).click(any(Locator.ClickOptions.class));
    }

    @Test
    public void regularClickOpensPriceFormWithoutRealSubmit() {
        Page page = mock(Page.class);
        Locator button = mock(Locator.class), inputs = mock(Locator.class), input = mock(Locator.class);
        when(page.getByTestId(NegotiationSelectors.OFFER_PRICE_INPUT)).thenReturn(inputs);
        when(inputs.first()).thenReturn(input);
        when(button.isEnabled()).thenReturn(true);
        form.openOfferModal(page, listing(), button);
        verify(button).click(any(Locator.ClickOptions.class));
        verify(verification).waitUntilVerified(page);
        verify(input).waitFor(any(Locator.WaitForOptions.class));
        verify(page, never()).getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON);
    }

    @Test
    public void failedNormalOpeningFallsBackToJavaScriptClickWithoutSubmit() {
        Page page = mock(Page.class);
        Locator button = mock(Locator.class), inputs = mock(Locator.class), input = mock(Locator.class);
        when(page.getByTestId(NegotiationSelectors.OFFER_PRICE_INPUT)).thenReturn(inputs);
        when(inputs.first()).thenReturn(input);
        when(button.isEnabled()).thenReturn(true);
        doThrow(new TimeoutError("not visible")).doNothing()
                .when(input).waitFor(any(Locator.WaitForOptions.class));
        form.openOfferModal(page, listing(), button);
        verify(button).click(any(Locator.ClickOptions.class));
        verify(button).evaluate("element => element.click()");
        verify(verification, times(2)).waitUntilVerified(page);
        verify(page, never()).getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON);
    }

    @Test
    public void tooLowWarningClosesModalAndNeverRequiresSubmit() {
        Page page = mock(Page.class);
        Locator inputs = mock(Locator.class), input = mock(Locator.class);
        Locator errors = mock(Locator.class), error = mock(Locator.class);
        Keyboard keyboard = mock(Keyboard.class);
        when(page.getByTestId(NegotiationSelectors.OFFER_PRICE_INPUT)).thenReturn(inputs);
        when(inputs.first()).thenReturn(input);
        when(input.inputValue()).thenReturn("1000.00");
        when(input.isVisible()).thenReturn(true);
        when(page.getByText(any(Pattern.class))).thenReturn(errors);
        when(errors.first()).thenReturn(error);
        when(page.keyboard()).thenReturn(keyboard);

        assertFalse(form.fillOfferPrice(page, listing(), new BigDecimal("1000.00")));

        verify(input).fill("1000.00");
        verify(input).press("Tab");
        verify(keyboard).press("Escape");
        verify(input).waitFor(any(Locator.WaitForOptions.class));
        verify(page, never()).getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON);
    }

    @Test
    public void noTooLowWarningOnlyChecksSubmitVisibilityAndEnabledState() {
        Page page = mock(Page.class);
        Locator inputs = mock(Locator.class), input = mock(Locator.class);
        Locator errors = mock(Locator.class), error = mock(Locator.class);
        Locator submits = mock(Locator.class), submit = mock(Locator.class);
        when(page.getByTestId(NegotiationSelectors.OFFER_PRICE_INPUT)).thenReturn(inputs);
        when(inputs.first()).thenReturn(input);
        when(input.inputValue()).thenReturn("1200.00");
        when(page.getByText(any(Pattern.class))).thenReturn(errors);
        when(errors.first()).thenReturn(error);
        doThrow(new TimeoutError("not visible")).when(error)
                .waitFor(any(Locator.WaitForOptions.class));
        when(page.getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON)).thenReturn(submits);
        when(submits.first()).thenReturn(submit);
        when(submit.isEnabled()).thenReturn(true);

        assertTrue(form.fillOfferPrice(page, listing(), new BigDecimal("1200.00")));

        verify(submit).waitFor(any(Locator.WaitForOptions.class));
        verify(submit, never()).click(any(Locator.ClickOptions.class));
    }

    @Test
    public void closingAlreadyHiddenFormDoesNotPressEscape() {
        Page page = mock(Page.class);
        Locator inputs = mock(Locator.class), input = mock(Locator.class);
        when(page.getByTestId(NegotiationSelectors.OFFER_PRICE_INPUT)).thenReturn(inputs);
        when(inputs.first()).thenReturn(input);
        when(input.isVisible()).thenReturn(false);
        form.closeOfferModal(page);
        verify(page, never()).keyboard();
    }

    private static ListingResponseDto listing() {
        ListingResponseDto listing = mock(ListingResponseDto.class);
        when(listing.listingId()).thenReturn("123");
        return listing;
    }
}
