package pl.flipbot.playwright.negotiation;

import org.junit.Test;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.model.NegotiationStepDto;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class FirstOfferPreflightPolicyTest {
    private final BotContext context = mock(BotContext.class);
    private final FirstOfferPreflightPolicy policy = new FirstOfferPreflightPolicy(context);

    @Test
    public void discoveredListingIsEligibleButNegotiatingStatusIsNot() {
        ListingResponseDto listing = listing("DISCOVERED", "2000.00");
        policy.validateListing(listing);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> policy.validateListing(listing("NEGOTIATING", "2000.00")))
                .getMessage().contains("Cannot start negotiation"));
    }

    @Test
    public void missingRequiredListingIdentityFailsBeforeBrowserAccess() {
        ListingResponseDto noId = mock(ListingResponseDto.class);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> policy.validateListing(noId)).getMessage().contains("Backend listing id"));
        ListingResponseDto noMarketplaceId = mock(ListingResponseDto.class);
        when(noMarketplaceId.id()).thenReturn(42L);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> policy.validateListing(noMarketplaceId)).getMessage().contains("Marketplace listing id"));
        ListingResponseDto noUrl = mock(ListingResponseDto.class);
        when(noUrl.id()).thenReturn(42L);
        when(noUrl.listingId()).thenReturn("123");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> policy.validateListing(noUrl)).getMessage().contains("Listing URL"));
    }

    @Test
    public void earliestNumberedStepIsUsedEvenIfConfigurationOrderDiffers() {
        configure(Arrays.asList(step(3, "1400.00"), null, step(1, "1200.00"), step(2, "1300.00")));
        NegotiationStepDto first = policy.getFirstNegotiationStep();
        assertEquals(Integer.valueOf(1), first.getStepNumber());
        assertEquals(new BigDecimal("1200.00"), first.getOfferPrice());
    }

    @Test
    public void missingConfigurationOrEmptyStepsFailsClosed() {
        BotDetailsDto bot = new BotDetailsDto();
        when(context.getBot()).thenReturn(bot);
        assertTrue(assertThrows(IllegalStateException.class,
                policy::getFirstNegotiationStep).getMessage().contains("configuration is missing"));
        BotConfigurationDto config = new BotConfigurationDto();
        config.setNegotiationSteps(List.of());
        bot.setConfiguration(config);
        assertTrue(assertThrows(IllegalStateException.class,
                policy::getFirstNegotiationStep).getMessage().contains("no negotiation steps"));
    }

    @Test
    public void firstStepRequiresPriceAndStrictlyPositiveValue() {
        configure(List.of(step(1, null)));
        assertTrue(assertThrows(IllegalStateException.class,
                policy::getFirstNegotiationStep).getMessage().contains("no offer price"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> policy.validateOfferPrice(BigDecimal.ZERO))
                .getMessage().contains("greater than zero"));
        policy.validateOfferPrice(new BigDecimal("0.01"));
    }

    @Test
    public void estimatedSixtyPercentThresholdIsEarlySkipOnly() {
        ListingResponseDto listing = listing("DISCOVERED", "2000.00");
        assertTrue(policy.isBelowEstimatedVintedMinimum(listing, new BigDecimal("1199.99")));
        assertFalse(policy.isBelowEstimatedVintedMinimum(listing, new BigDecimal("1200.00")));
        assertFalse(policy.isBelowEstimatedVintedMinimum(listing, new BigDecimal("1400.00")));
    }

    @Test
    public void missingOrZeroReferencePriceDoesNotTriggerEstimatedSkip() {
        assertFalse(policy.isBelowEstimatedVintedMinimum(listing("DISCOVERED", null), new BigDecimal("1.00")));
        assertFalse(policy.isBelowEstimatedVintedMinimum(listing("DISCOVERED", "0"), new BigDecimal("1.00")));
    }

    private void configure(List<NegotiationStepDto> steps) {
        BotConfigurationDto config = new BotConfigurationDto();
        config.setNegotiationSteps(steps);
        BotDetailsDto bot = new BotDetailsDto();
        bot.setConfiguration(config);
        when(context.getBot()).thenReturn(bot);
    }

    private static NegotiationStepDto step(int number, String price) {
        NegotiationStepDto step = new NegotiationStepDto();
        step.setStepNumber(number);
        if (price != null) step.setOfferPrice(new BigDecimal(price));
        return step;
    }

    private static ListingResponseDto listing(String status, String originalPrice) {
        ListingResponseDto listing = mock(ListingResponseDto.class);
        when(listing.id()).thenReturn(42L);
        when(listing.listingId()).thenReturn("123");
        when(listing.url()).thenReturn("https://www.vinted.pl/items/123-model");
        when(listing.status()).thenReturn(status);
        if (originalPrice != null) when(listing.originalPrice()).thenReturn(new BigDecimal(originalPrice));
        return listing;
    }
}
