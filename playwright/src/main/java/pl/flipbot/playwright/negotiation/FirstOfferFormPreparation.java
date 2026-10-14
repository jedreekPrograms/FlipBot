package pl.flipbot.playwright.negotiation;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Pre-quota-only Vinted first-offer form interactions.
 *
 * Opening the modal, entering/validating the price and closing on Vinted
 * minimum rejection are deliberately separated from real submit, quota
 * reservation, persistent action guards and prepared-offer ownership.
 * This class NEVER clicks OFFER_SUBMIT_BUTTON.
 */
@Slf4j
final class FirstOfferFormPreparation {
    private static final double FORM_OPEN_TIMEOUT_MS = 5_000;
    private static final double ELEMENT_TIMEOUT_MS = 15_000;
    private static final double OFFER_VALIDATION_TIMEOUT_MS = 1_500;
    private static final double MODAL_CLOSE_TIMEOUT_MS = 3_000;

    private final HumanVerificationHandler humanVerificationHandler;

    FirstOfferFormPreparation(HumanVerificationHandler humanVerificationHandler) {
        this.humanVerificationHandler = humanVerificationHandler;
    }

    void openOfferModal(
            Page page,
            ListingResponseDto listing,
            Locator offerButton
    ) {

        Locator priceInput =
                page.getByTestId(
                                NegotiationSelectors.OFFER_PRICE_INPUT
                        )
                        .first();

        if (
                priceInput.isVisible()
        ) {

            throw new IllegalStateException(
                    "Offer form was already visible before opening it for "
                            + "marketplace listing "
                            + listing.listingId()
            );
        }

        offerButton.scrollIntoViewIfNeeded();

        if (
                !offerButton.isEnabled()
        ) {

            throw new IllegalStateException(
                    "Offer button is visible but disabled for marketplace "
                            + "listing "
                            + listing.listingId()
            );
        }

        log.info(
                "[REAL OFFER PREPARE] Clicking offer button for marketplace "
                        + "listing {}. This only opens the offer form; it does "
                        + "NOT send an offer.",
                listing.listingId()
        );

        offerButton.click(
                new Locator.ClickOptions()
                        .setTimeout(
                                ELEMENT_TIMEOUT_MS
                        )
        );

        humanVerificationHandler.waitUntilVerified(
                page
        );

        if (
                waitForOfferForm(
                        priceInput,
                        FORM_OPEN_TIMEOUT_MS
                )
        ) {

            return;
        }

        log.warn(
                "[REAL OFFER PREPARE] Normal click did not open the offer "
                        + "form. Trying a JavaScript click."
        );

        offerButton.evaluate(
                "element => element.click()"
        );

        humanVerificationHandler.waitUntilVerified(
                page
        );

        if (
                waitForOfferForm(
                        priceInput,
                        FORM_OPEN_TIMEOUT_MS
                )
        ) {

            return;
        }

        throw new IllegalStateException(
                "Offer button was clicked, but offer-price-field--input did "
                        + "not appear. Marketplace listing: "
                        + listing.listingId()
                        + ", current URL: "
                        + page.url()
        );
    }

    boolean fillOfferPrice(
            Page page,
            ListingResponseDto listing,
            BigDecimal offerPrice
    ) {
        Locator priceInput = page.getByTestId(NegotiationSelectors.OFFER_PRICE_INPUT).first();
        String enteredValue = OfferPriceFormFields.fillAndVerify(
                priceInput, offerPrice.toPlainString(), ELEMENT_TIMEOUT_MS,
                "Offer input contains unexpected value. Expected: "
        );

        log.info(
                "[REAL OFFER PREPARE] Filled offer input for marketplace "
                        + "listing {}. Value={}. No offer has been sent.",
                listing.listingId(), enteredValue
        );

        priceInput.press("Tab");

        if (isOfferTooLow(page)) {
            closeOfferModal(page);
            return false;
        }

        OfferPriceFormFields.requireEnabledSubmit(
                page, ELEMENT_TIMEOUT_MS,
                "Offer submit button is disabled after filling price for "
                        + "marketplace listing " + listing.listingId()
        );
        return true;
    }

    private boolean waitForOfferForm(Locator priceInput, double timeoutMs) {
        return OfferFormVisibility.waitUntilVisible(priceInput, timeoutMs);
    }

    private boolean isOfferTooLow(
            Page page
    ) {

        Locator errorMessage =
                page.getByText(
                                Pattern.compile(
                                        "Wartość jest zbyt niska",
                                        Pattern.CASE_INSENSITIVE
                                )
                        )
                        .first();

        try {

            errorMessage.waitFor(
                    new Locator.WaitForOptions()
                            .setState(
                                    WaitForSelectorState.VISIBLE
                            )
                            .setTimeout(
                                    OFFER_VALIDATION_TIMEOUT_MS
                            )
            );

            return true;

        } catch (TimeoutError exception) {

            return false;
        }
    }

    void closeOfferModal(
            Page page
    ) {

        Locator priceInput =
                page.getByTestId(
                                NegotiationSelectors.OFFER_PRICE_INPUT
                        )
                        .first();

        if (
                !priceInput.isVisible()
        ) {

            return;
        }

        page.keyboard()
                .press(
                        "Escape"
                );

        try {

            priceInput.waitFor(
                    new Locator.WaitForOptions()
                            .setState(
                                    WaitForSelectorState.HIDDEN
                            )
                            .setTimeout(
                                    MODAL_CLOSE_TIMEOUT_MS
                            )
            );

        } catch (TimeoutError exception) {

            log.warn(
                    "[REAL OFFER PREPARE] Offer form did not disappear after "
                            + "pressing Escape."
            );
        }
    }
}
