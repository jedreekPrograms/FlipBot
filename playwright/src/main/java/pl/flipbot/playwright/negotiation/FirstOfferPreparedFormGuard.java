package pl.flipbot.playwright.negotiation;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;

import java.math.BigDecimal;

/**
 * Final safety check immediately BEFORE reserveSlot().
 * Rejects navigation away from the exact prepared item, disappearing modal,
 * any changed price input, or a disabled/invisible submit button.
 *
 * Does NOT reserve quota or click the real submit button; executor state
 * identity is separately verified first by requireMatchingPreparedOffer().
 */
@Slf4j
final class FirstOfferPreparedFormGuard {
    private static final double ELEMENT_TIMEOUT_MS = 15_000;

    private final FirstOfferListingReadiness listingReadiness;

    FirstOfferPreparedFormGuard(FirstOfferListingReadiness listingReadiness) {
        this.listingReadiness = listingReadiness;
    }

    void assertReady(Page page, ListingResponseDto listing, BigDecimal offerPrice) {
        if (
                !listingReadiness.isCurrentListingPage(
                        page,
                        listing.listingId()
                )
        ) {

            throw new IllegalStateException(
                    "Prepared offer page changed before quota reservation. "
                            + "Marketplace listing: "
                            + listing.listingId()
                            + ", current URL: "
                            + page.url()
            );
        }

        Locator priceInput =
                page.getByTestId(
                                NegotiationSelectors.OFFER_PRICE_INPUT
                        )
                        .first();

        if (
                !priceInput.isVisible()
        ) {

            throw new IllegalStateException(
                    "Offer form disappeared before quota reservation for "
                            + "marketplace listing "
                            + listing.listingId()
            );
        }

        String expectedPrice =
                offerPrice
                        .toPlainString();

        String actualPrice =
                priceInput.inputValue();

        if (
                !expectedPrice.equals(
                        actualPrice
                )
        ) {

            throw new IllegalStateException(
                    "Prepared offer price changed before quota reservation. "
                            + "Expected: "
                            + expectedPrice
                            + ", actual: "
                            + actualPrice
            );
        }

        Locator submitButton =
                page.getByTestId(
                                NegotiationSelectors.OFFER_SUBMIT_BUTTON
                        )
                        .first();

        submitButton.waitFor(
                new Locator.WaitForOptions()
                        .setState(
                                WaitForSelectorState.VISIBLE
                        )
                        .setTimeout(
                                ELEMENT_TIMEOUT_MS
                        )
        );

        if (
                !submitButton.isEnabled()
        ) {

            throw new IllegalStateException(
                    "Offer submit button is disabled before quota reservation "
                            + "for marketplace listing "
                            + listing.listingId()
            );
        }

        log.info(
                "[REAL OFFER PREPARE] Final pre-quota check passed for "
                        + "marketplace listing {}.",
                listing.listingId()
        );
    }
}
