package pl.flipbot.playwright.negotiation;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;

import java.math.BigDecimal;

/**
 * Non-authoritative, best-effort observation of the first price offer AFTER
 * a successful backend update. It cannot release quota, mutate listing state
 * or click the offer button; failures merely produce a diagnostic log.
 *
 * Strong evidence for ambiguous-submit recovery remains a separate,
 * fail-closed check in FirstOfferExecutor, and is never replaced by this.
 */
@Slf4j
final class FirstOfferPostSubmitObservation {
    private static final double CHAT_ELEMENT_TIMEOUT_MS = 20_000;

    void verifySubmittedOfferSafely(
            Page page,
            ListingResponseDto listing,
            BigDecimal expectedPrice
    ) {

        try {

            Locator ownOfferPrice =
                    page.getByTestId(
                                    NegotiationSelectors.OWN_OFFER_PRICE
                            )
                            .last();

            ownOfferPrice.waitFor(
                    new Locator.WaitForOptions()
                            .setState(
                                    WaitForSelectorState.VISIBLE
                            )
                            .setTimeout(
                                    CHAT_ELEMENT_TIMEOUT_MS
                            )
            );

            String rawPrice =
                    ownOfferPrice.innerText();

            BigDecimal actualPrice =
                    VintedPriceParser.parseFirstOfferConfirmation(
                            rawPrice
                    );

            if (
                    expectedPrice.compareTo(
                            actualPrice
                    ) != 0
            ) {

                log.error(
                        "[REAL OFFER] Submitted offer price does not match. "
                                + "Backend listing={}, expected={}, "
                                + "displayed={}, raw='{}'.",
                        listing.id(),
                        expectedPrice,
                        actualPrice,
                        rawPrice
                );

            } else {

                log.info(
                        "[REAL OFFER] Submitted offer price confirmed in "
                                + "conversation: {}.",
                        actualPrice
                );
            }

            logOwnOfferStatus(
                    page
            );

        } catch (Exception exception) {

            log.warn(
                    "[REAL OFFER] Could not fully verify submitted offer "
                            + "inside conversation for marketplace listing {}. "
                            + "Backend remains NEGOTIATING.",
                    listing.listingId()
            );

            log.trace(
                    "[REAL OFFER] Full submitted-offer verification error.",
                    exception
            );
        }
    }

    private void logOwnOfferStatus(
            Page page
    ) {

        Locator ownOfferStatus =
                page.getByTestId(
                                NegotiationSelectors.OWN_OFFER_STATUS
                        )
                        .last();

        try {

            ownOfferStatus.waitFor(
                    new Locator.WaitForOptions()
                            .setState(
                                    WaitForSelectorState.VISIBLE
                            )
                            .setTimeout(
                                    CHAT_ELEMENT_TIMEOUT_MS
                            )
            );

            log.info(
                    "[REAL OFFER] Current own-offer status displayed by "
                            + "Vinted: {}",
                    ownOfferStatus.innerText()
            );

        } catch (TimeoutError exception) {

            log.warn(
                    "[REAL OFFER] Own-offer status did not become visible "
                            + "inside conversation."
            );
        }
    }
}
