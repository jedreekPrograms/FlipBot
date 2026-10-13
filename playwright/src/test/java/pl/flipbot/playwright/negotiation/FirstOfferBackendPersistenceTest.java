package pl.flipbot.playwright.negotiation;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import pl.flipbot.playwright.api.listing.ListingClient;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.api.listing.dto.UpdateListingRequestDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotDetailsDto;

import java.math.BigDecimal;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class FirstOfferBackendPersistenceTest {
    private final BotContext context = mock(BotContext.class);
    private final ListingClient client = mock(ListingClient.class);
    private final FirstOfferBackendPersistence persistence =
            new FirstOfferBackendPersistence(context, client);

    @Test
    public void persistsFirstOfferUsingExplicitConversationAndExpectedPrice() {
        configure(listing("NEGOTIATING", "inbox-123"));
        ListingResponseDto before = listing("DISCOVERED", null);
        ListingResponseDto after = persistence.markNegotiationStarted(
                before, new BigDecimal("1200.00"), 1,
                "inbox-123", "https://www.vinted.pl/inbox/inbox-123?referrer=123");
        assertEquals("NEGOTIATING", after.status());

        ArgumentCaptor<UpdateListingRequestDto> captured =
                ArgumentCaptor.forClass(UpdateListingRequestDto.class);
        verify(client, times(1)).updateListing(eq(7L), eq(42L), captured.capture());
        assertEquals("NEGOTIATING", captured.getValue().status());
        assertEquals(new BigDecimal("1200.00"), captured.getValue().currentPrice());
        assertEquals(Integer.valueOf(1), captured.getValue().currentStep());
        assertEquals(Boolean.TRUE, captured.getValue().awaitingSellerResponse());
        assertEquals("inbox-123", captured.getValue().conversationId());
        assertEquals("https://www.vinted.pl/inbox/inbox-123?referrer=123",
                captured.getValue().conversationUrl());
    }

    @Test
    public void unexpectedBackendStatusIsNotAccepted() {
        configure(listing("DISCOVERED", "inbox-123"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> persistence.markNegotiationStarted(
                        listing("DISCOVERED", null), new BigDecimal("1200"), 1,
                        "inbox-123", "https://www.vinted.pl/inbox/inbox-123"))
                .getMessage().contains("unexpected listing status"));
    }

    @Test
    public void unexpectedConversationIdIsNotAccepted() {
        configure(listing("NEGOTIATING", "different-inbox"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> persistence.markNegotiationStarted(
                        listing("DISCOVERED", null), new BigDecimal("1200"), 1,
                        "inbox-123", "https://www.vinted.pl/inbox/inbox-123"))
                .getMessage().contains("unexpected conversation ID"));
    }

    @Test
    public void failedBackendUpdateDoesNotSilentlyPass() {
        BotDetailsDto bot = new BotDetailsDto();
        bot.setId(7L);
        when(context.getBot()).thenReturn(bot);
        when(client.updateListing(eq(7L), eq(42L), any(UpdateListingRequestDto.class)))
                .thenThrow(new IllegalStateException("Backend unavailable"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> persistence.markNegotiationStarted(
                        listing("DISCOVERED", null), new BigDecimal("1200"), 1,
                        "inbox-123", "https://www.vinted.pl/inbox/inbox-123"))
                .getMessage().contains("Backend unavailable"));
    }

    private void configure(ListingResponseDto response) {
        BotDetailsDto bot = new BotDetailsDto();
        bot.setId(7L);
        when(context.getBot()).thenReturn(bot);
        when(client.updateListing(eq(7L), eq(42L), any(UpdateListingRequestDto.class)))
                .thenReturn(response);
    }

    private static ListingResponseDto listing(String status, String conversationId) {
        ListingResponseDto listing = mock(ListingResponseDto.class);
        when(listing.id()).thenReturn(42L);
        when(listing.status()).thenReturn(status);
        when(listing.conversationId()).thenReturn(conversationId);
        return listing;
    }
}
