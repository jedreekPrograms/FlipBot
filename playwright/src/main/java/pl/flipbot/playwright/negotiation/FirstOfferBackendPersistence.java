package pl.flipbot.playwright.negotiation;

import pl.flipbot.playwright.api.listing.ListingClient;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.api.listing.dto.UpdateListingRequestDto;
import pl.flipbot.playwright.context.BotContext;

import java.math.BigDecimal;

/**
 * Persist the first negotiation state only after the Vinted submit has
 * navigated to a conversation, or after fail-closed ambiguous-submit
 * reconciliation has obtained its independent matching-price evidence.
 *
 * Does not inspect the browser, reserve/release quota or click submit.
 * Retains the original backend response assertions and update payload.
 */
final class FirstOfferBackendPersistence {
    private final BotContext context;
    private final ListingClient listingClient;

    FirstOfferBackendPersistence(BotContext context, ListingClient listingClient) {
        this.context = context;
        this.listingClient = listingClient;
    }

    ListingResponseDto markNegotiationStarted(
            ListingResponseDto listing,
            BigDecimal offerPrice,
            Integer stepNumber,
            String conversationId,
            String conversationUrl
    ) {

        UpdateListingRequestDto request =
                new UpdateListingRequestDto(
                        "NEGOTIATING",
                        offerPrice,
                        stepNumber,
                        true,
                        conversationId,
                        conversationUrl
                );

        ListingResponseDto updatedListing =
                listingClient.updateListing(
                        context.getBot()
                                .getId(),
                        listing.id(),
                        request
                );

        if (
                !"NEGOTIATING".equals(
                        updatedListing.status()
                )
        ) {

            throw new IllegalStateException(
                    "Backend returned unexpected listing status after "
                            + "starting negotiation. Expected NEGOTIATING, "
                            + "actual: "
                            + updatedListing.status()
            );
        }

        if (
                !conversationId.equals(
                        updatedListing.conversationId()
                )
        ) {

            throw new IllegalStateException(
                    "Backend returned unexpected conversation ID. Expected: "
                            + conversationId
                            + ", actual: "
                            + updatedListing.conversationId()
            );
        }

        return updatedListing;
    }
}
