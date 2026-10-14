package pl.flipbot.playwright.negotiation;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.Test;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class FirstOfferMessageDeliveryTest {
    private final HumanVerificationHandler handler = mock(HumanVerificationHandler.class);
    private final FirstOfferMessageDelivery sender = new FirstOfferMessageDelivery(handler);

    @Test
    public void blankMessageDoesNotUsePageOrSubmitAnyOffer() {
        Page page = mock(Page.class);
        sender.sendFirstMessageSafely(page, listing(), null);
        sender.sendFirstMessageSafely(page, listing(), " ");
        verifyNoInteractions(page, handler);
    }

    @Test
    public void exactMessageIsSentOnceWithoutAccessingPriceSubmit() {
        Page page = mock(Page.class);
        Locator inputMatches = mock(Locator.class), input = mock(Locator.class);
        Locator iconMatches = mock(Locator.class), icon = mock(Locator.class);
        Locator parentMatches = mock(Locator.class), button = mock(Locator.class);
        when(page.getByTestId(NegotiationSelectors.MESSAGE_INPUT)).thenReturn(inputMatches);
        when(inputMatches.first()).thenReturn(input);
        when(input.inputValue()).thenReturn("Hello!", "");
        when(page.getByTestId(NegotiationSelectors.MESSAGE_SEND_ICON)).thenReturn(iconMatches);
        when(iconMatches.last()).thenReturn(icon);
        when(icon.locator("xpath=ancestor::button[1]")).thenReturn(parentMatches);
        when(parentMatches.first()).thenReturn(button);

        sender.sendFirstMessageSafely(page, listing(), "Hello!");

        verify(handler).waitUntilVerified(page);
        verify(input).fill("Hello!");
        verify(button, times(1)).click(any(Locator.ClickOptions.class));
        verify(page, never()).getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON);
    }

    @Test
    public void mismatchedComposerRefusesChatButtonAndSuppressesError() {
        Page page = mock(Page.class);
        Locator matches = mock(Locator.class), input = mock(Locator.class);
        when(page.getByTestId(NegotiationSelectors.MESSAGE_INPUT)).thenReturn(matches);
        when(matches.first()).thenReturn(input);
        when(input.inputValue()).thenReturn("different");

        sender.sendFirstMessageSafely(page, listing(), "Hello!");

        verify(page, never()).getByTestId(NegotiationSelectors.MESSAGE_SEND_ICON);
        verify(page, never()).getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON);
    }

    @Test
    public void failedChatClickCannotCauseExceptionOrPriceResubmission() {
        Page page = mock(Page.class);
        Locator inputs = mock(Locator.class), input = mock(Locator.class);
        Locator icons = mock(Locator.class), icon = mock(Locator.class);
        Locator parents = mock(Locator.class), button = mock(Locator.class);
        when(page.getByTestId(NegotiationSelectors.MESSAGE_INPUT)).thenReturn(inputs);
        when(inputs.first()).thenReturn(input);
        when(input.inputValue()).thenReturn("Hello!");
        when(page.getByTestId(NegotiationSelectors.MESSAGE_SEND_ICON)).thenReturn(icons);
        when(icons.last()).thenReturn(icon);
        when(icon.locator("xpath=ancestor::button[1]")).thenReturn(parents);
        when(parents.first()).thenReturn(button);
        doThrow(new IllegalStateException("Chat disconnected"))
                .when(button).click(any(Locator.ClickOptions.class));

        sender.sendFirstMessageSafely(page, listing(), "Hello!");

        verify(button, times(1)).click(any(Locator.ClickOptions.class));
        verify(page, never()).getByTestId(NegotiationSelectors.OFFER_SUBMIT_BUTTON);
    }

    private static ListingResponseDto listing() {
        ListingResponseDto l = mock(ListingResponseDto.class);
        when(l.listingId()).thenReturn("123");
        return l;
    }
}
