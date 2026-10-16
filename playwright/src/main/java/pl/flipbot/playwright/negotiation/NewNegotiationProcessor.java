package pl.flipbot.playwright.negotiation;

import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.ListingClient;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.api.listing.dto.UpdateListingRequestDto;
import pl.flipbot.playwright.api.quota.OfferQuotaClient;
import pl.flipbot.playwright.api.quota.dto.OfferQuotaReservationResponseDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.target.ListingDetailTargetInspector;
import pl.flipbot.playwright.target.ListingTargetAssessment;
import pl.flipbot.playwright.target.ListingTargetMatcher;
import pl.flipbot.playwright.target.ListingUnavailableDuringVerificationException;
import pl.flipbot.playwright.target.VintedRateLimitException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
public class NewNegotiationProcessor {

    private static final String VINTED_MODEL = "VINTED_MODEL";
    private static final int MAX_DETAIL_INSPECTIONS_PER_CYCLE = 5;
    private static final int MAX_FINAL_VERIFICATIONS_PER_CYCLE = 20;
    private static final int PREPARATION_FALLBACK_CANDIDATES = 5;


    private final BotContext context;
    private final ListingClient listingClient;
    private final OfferQuotaClient offerQuotaClient;
    private final ListingStatusUpdater listingStatusUpdater;
    private final FirstOfferExecutor firstOfferExecutor;
    private final FirstOfferActionGuardCoordinator firstOfferActionGuardCoordinator;
    private final ListingTargetMatcher listingTargetMatcher;
    private final ListingDetailTargetInspector listingDetailTargetInspector;
    private final CatalogDetailInspectionBudget detailInspectionBudget;
    private final boolean realOffersEnabled;
    private final int maxRealOffersPerRun;

    public NewNegotiationProcessor(
            BotContext context,
            ListingClient listingClient,
            OfferQuotaClient offerQuotaClient,
            ListingStatusUpdater listingStatusUpdater,
            boolean realOffersEnabled,
            int maxRealOffersPerRun
    ) {
        this(
                context,
                listingClient,
                offerQuotaClient,
                listingStatusUpdater,
                realOffersEnabled,
                maxRealOffersPerRun,
                new CatalogDetailInspectionBudget(
                        MAX_DETAIL_INSPECTIONS_PER_CYCLE
                                + MAX_FINAL_VERIFICATIONS_PER_CYCLE
                )
        );
    }

    public NewNegotiationProcessor(
            BotContext context,
            ListingClient listingClient,
            OfferQuotaClient offerQuotaClient,
            ListingStatusUpdater listingStatusUpdater,
            boolean realOffersEnabled,
            int maxRealOffersPerRun,
            CatalogDetailInspectionBudget detailInspectionBudget
    ) {
        if (detailInspectionBudget == null) {
            throw new IllegalArgumentException(
                    "Catalog detail inspection budget is required."
            );
        }

        this.context = context;
        this.listingClient = listingClient;
        this.offerQuotaClient = offerQuotaClient;
        this.listingStatusUpdater = listingStatusUpdater;
        this.firstOfferExecutor = new AdaptiveFirstOfferExecutor(context);
        this.firstOfferActionGuardCoordinator = new FirstOfferActionGuardCoordinator();
        this.listingTargetMatcher = new ListingTargetMatcher();
        this.listingDetailTargetInspector = new ListingDetailTargetInspector(
                context,
                listingTargetMatcher
        );
        this.detailInspectionBudget = detailInspectionBudget;
        this.realOffersEnabled = realOffersEnabled;
        this.maxRealOffersPerRun = maxRealOffersPerRun;
    }

    /**
     * @param currentScanListingIds IDs actually observed in THIS already-filtered
     * current catalog scan. For VINTED_MODEL these are the only persisted rows
     * allowed to inherit current exact-model-filter provenance for this cycle.
     */
    public void process(
            List<ListingResponseDto> priceEligibleListings,
            Set<String> currentScanListingIds
    ) {
        if (priceEligibleListings == null || priceEligibleListings.isEmpty()) {
            log.info("[REAL OFFER] There are no price-eligible listings to process.");
            return;
        }

        Set<String> currentScanIds = currentScanListingIds == null
                ? Set.of()
                : Set.copyOf(currentScanListingIds);

        BotConfigurationDto configuration = context.getBot().getConfiguration();
        if (configuration == null) {
            throw new IllegalStateException("Bot configuration is missing");
        }

        List<ListingResponseDto> targetEligibleListings =
                new NewNegotiationTargetEligibility(
                        context,
                        listingStatusUpdater,
                        listingTargetMatcher,
                        listingDetailTargetInspector,
                        detailInspectionBudget
                ).retainTargetEligibleListings(
                        priceEligibleListings,
                        configuration,
                        currentScanIds
                );

        if (targetEligibleListings.isEmpty()) {
            log.warn(
                    "[TARGET MATCHER] None of the {} price-eligible DISCOVERED candidates matches the configured target. No quota will be reserved and no negotiation will be started.",
                    priceEligibleListings.size()
            );
            return;
        }

        Long botId = context.getBot().getId();
        int allowedNewNegotiations = listingClient.getAllowedNewNegotiations(botId);

        if (allowedNewNegotiations <= 0) {
            log.info("[REAL OFFER] Bot {} cannot start any new negotiations.", botId);
            return;
        }

        if (!realOffersEnabled) {
            processDryRun(
                    targetEligibleListings,
                    configuration,
                    currentScanIds,
                    allowedNewNegotiations
            );
            return;
        }

        processRealOffers(
                targetEligibleListings,
                configuration,
                currentScanIds,
                botId,
                allowedNewNegotiations
        );
    }

    private void processDryRun(
            List<ListingResponseDto> targetEligibleListings,
            BotConfigurationDto configuration,
            Set<String> currentScanListingIds,
            int allowedNewNegotiations
    ) {
        int desiredVerified = Math.min(
                targetEligibleListings.size(),
                allowedNewNegotiations
        );

        NewNegotiationFinalCandidateVerifier.Result finalVerification = new NewNegotiationFinalCandidateVerifier(
                        context,
                        listingStatusUpdater,
                        listingDetailTargetInspector,
                        detailInspectionBudget
                ).verifyFinalCandidates(
                targetEligibleListings,
                configuration,
                currentScanListingIds,
                MAX_FINAL_VERIFICATIONS_PER_CYCLE,
                desiredVerified
        );

        log.warn(
                "[REAL OFFER DRY RUN] Real offers are disabled. {} target-eligible DISCOVERED candidates were found. {} candidate(s) were checked by final target verification. {} passed, {} failed target verification, {} could not be verified. Backend allows {} new negotiations. No quota was reserved and no offer was sent.",
                targetEligibleListings.size(),
                finalVerification.checked(),
                finalVerification.verifiedListings().size(),
                finalVerification.mismatches(),
                finalVerification.failures(),
                allowedNewNegotiations
        );
    }

    private void processRealOffers(
            List<ListingResponseDto> targetEligibleListings,
            BotConfigurationDto configuration,
            Set<String> currentScanListingIds,
            Long botId,
            int allowedNewNegotiations
    ) {
        int maximumOffersThisRun = Math.min(
                allowedNewNegotiations,
                maxRealOffersPerRun
        );

        if (maximumOffersThisRun <= 0) {
            log.warn(
                    "[REAL OFFER] Real offers are enabled, but maxRealOffersPerRun={} prevents starting any offer.",
                    maxRealOffersPerRun
            );
            return;
        }

        int desiredVerifiedCandidates = Math.min(
                targetEligibleListings.size(),
                Math.min(
                        MAX_FINAL_VERIFICATIONS_PER_CYCLE,
                        maximumOffersThisRun + PREPARATION_FALLBACK_CANDIDATES
                )
        );

        log.warn(
                "[REAL OFFER] Real offers are enabled. Bot {} has {} target-eligible DISCOVERED candidates. Backend allows {} new negotiations. This run is limited to {} real offer(s). Final target verification will inspect up to {} candidates until {} verified candidate(s) are found ({} capacity + up to {} preparation fallbacks). Quota is reserved only after the form is fully prepared and submit is ready.",
                botId,
                targetEligibleListings.size(),
                allowedNewNegotiations,
                maximumOffersThisRun,
                MAX_FINAL_VERIFICATIONS_PER_CYCLE,
                desiredVerifiedCandidates,
                maximumOffersThisRun,
                PREPARATION_FALLBACK_CANDIDATES
        );

        NewNegotiationFinalCandidateVerifier.Result finalVerification = new NewNegotiationFinalCandidateVerifier(
                        context,
                        listingStatusUpdater,
                        listingDetailTargetInspector,
                        detailInspectionBudget
                ).verifyFinalCandidates(
                targetEligibleListings,
                configuration,
                currentScanListingIds,
                MAX_FINAL_VERIFICATIONS_PER_CYCLE,
                desiredVerifiedCandidates
        );

        List<ListingResponseDto> finalVerifiedListings = finalVerification.verifiedListings();

        if (finalVerifiedListings.isEmpty()) {
            log.warn(
                    "[REAL OFFER] No candidate passed mandatory final target verification. No quota will be reserved and no offer will be sent."
            );
            return;
        }

        log.info(
                "[REAL OFFER] {} candidate(s) passed mandatory final target verification. They may be tried in order until {} real negotiation(s) are started.",
                finalVerifiedListings.size(),
                maximumOffersThisRun
        );

        int startedNegotiations = 0;

        for (ListingResponseDto listing : finalVerifiedListings) {
            if (startedNegotiations >= maximumOffersThisRun) {
                break;
            }

            NegotiationPreparationResult preparationResult;

            try {
                preparationResult = firstOfferExecutor.prepareFirstOffer(listing);
            } catch (VintedRateLimitException exception) {
                throw exception;
            } catch (Exception exception) {
                log.error(
                        "[REAL OFFER PREPARE] Failed before quota reservation for marketplace listing {}: {}. No quota was reserved and no offer was sent. Trying the next verified candidate.",
                        listing.listingId(),
                        getFriendlyErrorMessage(exception)
                );
                log.trace(
                        "[REAL OFFER PREPARE] Full preparation error for marketplace listing {}.",
                        listing.listingId(),
                        exception
                );
                firstOfferExecutor.cancelPreparedOfferSafely();
                continue;
            }

            if (preparationResult == NegotiationPreparationResult.LISTING_UNAVAILABLE) {
                listingStatusUpdater.markUnavailable(botId, listing);
                continue;
            }

            if (preparationResult == NegotiationPreparationResult.TARGET_MISMATCH) {
                listingStatusUpdater.markTargetMismatch(botId, listing);
                continue;
            }

            if (preparationResult == NegotiationPreparationResult.OFFER_TOO_LOW) {
                listingStatusUpdater.markOfferTooLow(botId, listing);
                continue;
            }

            if (preparationResult == NegotiationPreparationResult.CANNOT_NEGOTIATE) {
                markCannotNegotiate(botId, listing);
                log.warn(
                        "[REAL OFFER] Skipping marketplace listing {} because Vinted exposes no negotiation action for this account. Backend status is now SKIPPED_CANNOT_NEGOTIATE. Trying the next fully verified candidate. No quota was reserved.",
                        listing.listingId()
                );
                continue;
            }

            if (preparationResult != NegotiationPreparationResult.PREPARED) {
                firstOfferExecutor.cancelPreparedOfferSafely();
                throw new IllegalStateException(
                        "Unexpected negotiation preparation result: " + preparationResult
                );
            }

            try {
                firstOfferExecutor.assertPreparedOfferReady(listing);
            } catch (Exception exception) {
                log.error(
                        "[REAL OFFER PREPARE] Prepared form became invalid before quota reservation for marketplace listing {}: {}. No quota was reserved. Trying the next verified candidate.",
                        listing.listingId(),
                        getFriendlyErrorMessage(exception)
                );
                log.trace(
                        "[REAL OFFER PREPARE] Full pre-quota readiness error for marketplace listing {}.",
                        listing.listingId(),
                        exception
                );
                firstOfferExecutor.cancelPreparedOfferSafely();
                continue;
            }

            var actionGuardRequestId = firstOfferActionGuardCoordinator.acquire(
                    botId,
                    listing
            );

            if (actionGuardRequestId == null) {
                firstOfferExecutor.cancelPreparedOfferSafely();
                log.warn(
                        "[REAL OFFER] FIRST_OFFER action guard refused marketplace listing {} (for example because another bot already owns that marketplace negotiation). No quota was reserved and no real submit was attempted. Trying the next verified candidate instead of wasting this run's capacity.",
                        listing.listingId()
                );
                continue;
            }

            log.warn(
                    "[REAL OFFER] Marketplace listing {} passed every pre-submit guard. Persistent FIRST_OFFER guard is acquired. Reserving quota now, immediately before the real submit click.",
                    listing.listingId()
            );

            OfferQuotaReservationResponseDto quotaReservation;

            try {
                quotaReservation = offerQuotaClient.reserveSlot(
                        botId,
                        actionGuardRequestId
                );
            } catch (Exception exception) {
                firstOfferActionGuardCoordinator.releaseBeforeSubmitSafely(
                        botId,
                        listing,
                        actionGuardRequestId,
                        "quota reservation failed before real submit"
                );
                firstOfferExecutor.cancelPreparedOfferSafely();
                throw exception;
            }

            if (!quotaReservation.reserved()) {
                firstOfferActionGuardCoordinator.releaseBeforeSubmitSafely(
                        botId,
                        listing,
                        actionGuardRequestId,
                        "daily quota was not reserved"
                );

                log.warn(
                        "[REAL OFFER] Daily offer quota exhausted for bot {}. Used: {}/{}, remaining: {}. Prepared form will be closed and no offer will be sent.",
                        botId,
                        quotaReservation.used(),
                        quotaReservation.limit(),
                        quotaReservation.remaining()
                );

                firstOfferExecutor.cancelPreparedOfferSafely();
                return;
            }

            try {
                NegotiationStartResult result = firstOfferExecutor
                        .submitPreparedFirstNegotiation(listing);

                if (result != NegotiationStartResult.STARTED) {
                    throw new IllegalStateException(
                            "Unexpected negotiation start result after prepared submit: " + result
                    );
                }

                firstOfferActionGuardCoordinator.releaseAfterConfirmedSuccessBestEffort(
                        botId,
                        listing,
                        actionGuardRequestId
                );

                startedNegotiations++;

                log.warn(
                        "[REAL OFFER] Real negotiation STARTED for marketplace listing {}. Started during this run: {}/{}. Daily quota used: {}/{}, remaining: {}.",
                        listing.listingId(),
                        startedNegotiations,
                        maximumOffersThisRun,
                        quotaReservation.used(),
                        quotaReservation.limit(),
                        quotaReservation.remaining()
                );

            } catch (Exception exception) {
                boolean reconciled =
                        firstOfferExecutor.reconcilePreparedFirstNegotiationAfterAmbiguousSubmit(
                                listing
                        );

                if (reconciled) {
                    firstOfferActionGuardCoordinator.releaseAfterConfirmedSuccessBestEffort(
                            botId,
                            listing,
                            actionGuardRequestId
                    );

                    startedNegotiations++;

                    log.warn(
                            "[REAL OFFER] Post-submit failure for listing {} was reconciled from strong Vinted conversation evidence. "
                                    + "The backend is NEGOTIATING and no duplicate offer was sent. Original error: {}",
                            listing.listingId(),
                            getFriendlyErrorMessage(exception)
                    );
                    continue;
                }

                log.error(
                        "[REAL OFFER] Failure occurred after quota reservation while submitting marketplace listing {}: {}. "
                                + "Strong reconciliation could not prove delivery. Quota will NOT be released automatically and FIRST_OFFER action guard remains persisted to prevent a duplicate offer.",
                        listing.listingId(),
                        getFriendlyErrorMessage(exception)
                );
                log.trace(
                        "[REAL OFFER] Full post-reservation submission error for marketplace listing {}.",
                        listing.listingId(),
                        exception
                );
                throw exception;
            }
        }

        log.info(
                "[REAL OFFER] Finished real-offer processing. Started {} negotiation(s).",
                startedNegotiations
        );
    }

    private void markCannotNegotiate(
            Long botId,
            ListingResponseDto listing
    ) {
        BigDecimal currentPrice = listing.currentPrice() != null
                ? listing.currentPrice()
                : listing.originalPrice();

        if (currentPrice == null) {
            throw new IllegalStateException(
                    "Cannot mark backend listing " + listing.id()
                            + " as SKIPPED_CANNOT_NEGOTIATE because its price is null"
            );
        }

        Integer currentStep = listing.currentStep() != null
                ? listing.currentStep()
                : 0;

        UpdateListingRequestDto request = new UpdateListingRequestDto(
                "SKIPPED_CANNOT_NEGOTIATE",
                currentPrice,
                currentStep,
                false,
                null,
                null
        );

        ListingResponseDto updatedListing = listingClient.updateListing(
                botId,
                listing.id(),
                request
        );

        if (!"SKIPPED_CANNOT_NEGOTIATE".equals(updatedListing.status())) {
            throw new IllegalStateException(
                    "Backend returned an unexpected status after marking listing "
                            + listing.id()
                            + " as SKIPPED_CANNOT_NEGOTIATE. Actual: "
                            + updatedListing.status()
            );
        }

        log.info(
                "[CANNOT NEGOTIATE] Backend listing {} / marketplace listing {} was marked as SKIPPED_CANNOT_NEGOTIATE. No quota was reserved.",
                updatedListing.id(),
                listing.listingId()
        );
    }





    static boolean hasCurrentExactModelProof(
            ListingResponseDto listing,
            BotConfigurationDto configuration,
            Set<String> currentScanListingIds
    ) {
        return listing != null
                && listing.listingId() != null
                && usesExactVintedModelFilter(configuration)
                && currentScanListingIds != null
                && currentScanListingIds.contains(listing.listingId());
    }

    static boolean usesExactVintedModelFilter(
            BotConfigurationDto configuration
    ) {
        if (configuration == null) {
            return false;
        }

        String targetMode = configuration.getTargetMode();
        return targetMode == null
                || targetMode.isBlank()
                || VINTED_MODEL.equalsIgnoreCase(targetMode.trim());
    }

    static String getConfiguredTargetLabel(
            BotConfigurationDto configuration
    ) {
        if (configuration == null) {
            return "null";
        }

        if ("SEARCH_QUERY".equalsIgnoreCase(configuration.getTargetMode())) {
            return configuration.getSearchQuery();
        }

        if (usesExactVintedModelFilter(configuration)) {
            return configuration.getModel();
        }

        return "unknown";
    }

    static String getFriendlyErrorMessage(Throwable exception) {
        if (exception == null) {
            return "Unknown error";
        }

        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }

        int firstLineEnd = message.indexOf('\n');
        if (firstLineEnd > 0) {
            return message.substring(0, firstLineEnd).trim();
        }

        return message.trim();
    }


}
