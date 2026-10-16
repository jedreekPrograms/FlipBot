package pl.flipbot.playwright.negotiation;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

/**
 * Optional first-offer message, invoked only AFTER the price was sent and
 * the backend marked NEGOTIATING (including a strongly proven ambiguous
 * submit recovery). If chat delivery fails, never re-submit the price.
 *
 * Keeps the original 20-second selector waits, 5-second composer-clear
 * check and conservative log-only handling of failures.
 */
@Slf4j
final class FirstOfferMessageDelivery {
    private static final double CHAT_ELEMENT_TIMEOUT_MS = 20_000;
    private static final double MESSAGE_CONFIRMATION_TIMEOUT_MS = 5_000;
    private static final double MESSAGE_CONFIRMATION_POLL_INTERVAL_MS = 250;

    private final HumanVerificationHandler humanVerificationHandler;

    FirstOfferMessageDelivery(HumanVerificationHandler handler) {
        this.humanVerificationHandler = handler;
    }

    void sendFirstMessageSafely(
            Page page,
            ListingResponseDto listing,
            String message
    ) {

        if (
                message == null
                        || message.isBlank()
        ) {

            log.info(
                    "[REAL OFFER] First negotiation step has no message. "
                            + "Only the price offer was sent for listing {}.",
                    listing.listingId()
            );

            return;
        }

        try {

            humanVerificationHandler.waitUntilVerified(
                    page
            );

            Locator messageInput = NegotiationMessageComposer.fillAndVerify(
                    page, message, CHAT_ELEMENT_TIMEOUT_MS, "Chat input contains unexpected message"
            );
            Locator sendButton = NegotiationMessageComposer.requireSendButton(
                    page, CHAT_ELEMENT_TIMEOUT_MS
            );

            sendButton.click(
                    new Locator.ClickOptions()
                            .setTimeout(
                                    CHAT_ELEMENT_TIMEOUT_MS
                            )
            );

            if (
                    NegotiationMessageComposer.awaitClear(
                            page, messageInput, MESSAGE_CONFIRMATION_TIMEOUT_MS,
                            MESSAGE_CONFIRMATION_POLL_INTERVAL_MS
                    )
            ) {

                log.info(
                        "[REAL OFFER] Negotiation message was sent for "
                                + "marketplace listing {}.",
                        listing.listingId()
                );

            } else {

                log.warn(
                        "[REAL OFFER] Send button was clicked, but the message "
                                + "input did not clear for listing {}. "
                                + "Manual verification may be required.",
                        listing.listingId()
                );
            }

        } catch (Exception exception) {

            log.error(
                    "[REAL OFFER] Price offer was sent, but the negotiation "
                            + "message could not be sent for marketplace "
                            + "listing {}. Backend remains NEGOTIATING.",
                    listing.listingId()
            );

            log.trace(
                    "[REAL OFFER] Full first-message error.",
                    exception
            );
        }
    }
}
