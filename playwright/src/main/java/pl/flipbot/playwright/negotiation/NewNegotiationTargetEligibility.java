package pl.flipbot.playwright.negotiation;

import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.target.ListingDetailTargetInspector;
import pl.flipbot.playwright.target.ListingTargetAssessment;
import pl.flipbot.playwright.target.ListingTargetMatcher;
import pl.flipbot.playwright.target.ListingUnavailableDuringVerificationException;
import pl.flipbot.playwright.target.VintedRateLimitException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pre-quota eligibility for first-offer candidates.
 *
 * CURRENT native VINTED_MODEL scan evidence is authoritative; persisted rows
 * outside that scan are deferred without inferring a model from seller text.
 * SEARCH_QUERY still requires explicit catalog/URL or cached/live detail
 * identity, with shared budgets, pacing and conservative error handling.
 * This component never reserves quota or submits an offer.
 */
@Slf4j
final class NewNegotiationTargetEligibility {
    private static final int MAX_DETAIL_INSPECTIONS_PER_CYCLE = 5;
    private static final double DETAIL_INSPECTION_PACING_MS = 1_500;

    private final BotContext context;
    private final ListingStatusUpdater listingStatusUpdater;
    private final ListingTargetMatcher listingTargetMatcher;
    private final ListingDetailTargetInspector listingDetailTargetInspector;
    private final CatalogDetailInspectionBudget detailInspectionBudget;

    NewNegotiationTargetEligibility(
            BotContext context,
            ListingStatusUpdater listingStatusUpdater,
            ListingTargetMatcher listingTargetMatcher,
            ListingDetailTargetInspector listingDetailTargetInspector,
            CatalogDetailInspectionBudget detailInspectionBudget
    ) {
        this.context = context;
        this.listingStatusUpdater = listingStatusUpdater;
        this.listingTargetMatcher = listingTargetMatcher;
        this.listingDetailTargetInspector = listingDetailTargetInspector;
        this.detailInspectionBudget = detailInspectionBudget;
    }

    List<ListingResponseDto> retainTargetEligibleListings(
            List<ListingResponseDto> listings,
            BotConfigurationDto configuration,
            Set<String> currentScanListingIds
    ) {
        List<ListingResponseDto> eligibleListings = new ArrayList<>();

        int acceptedFromCurrentExactScan = 0;
        int matchedFromCatalogTitle = 0;
        int matchedFromUrlSlug = 0;
        int matchedFromDetailCache = 0;
        int matchedAfterDetailRequest = 0;
        int rejectedCatalogMismatch = 0;
        int rejectedUrlMismatch = 0;
        int rejectedFromDetailCache = 0;
        int rejectedAfterDetailRequest = 0;
        int detailInspectionFailures = 0;
        int deferredByDetailLimit = 0;
        int deferredByGlobalDetailBudget = 0;
        int detailRequestsThisCycle = 0;
        int persistedTargetMismatches = 0;
        int persistedUnavailable = 0;
        int deferredVintedModelOutsideCurrentScan = 0;
        Long botId = context.getBot().getId();

        for (ListingResponseDto listing : listings) {
            boolean currentExactModelProof = NewNegotiationProcessor.hasCurrentExactModelProof(
                    listing,
                    configuration,
                    currentScanListingIds
            );

            /*
             * VINTED_MODEL trusts the CURRENT native Vinted model-filter result
             * set. There is deliberately no title/URL/item-page target guard in
             * this mode.
             *
             * Persisted backlog rows that are not visible in the current exact
             * model-filter scan are deferred until a future current scan. They
             * are not reclassified by seller text and cannot reach submit from
             * stale provenance alone.
             */
            if (NewNegotiationProcessor.usesExactVintedModelFilter(configuration)) {
                if (currentExactModelProof) {
                    eligibleListings.add(listing);
                    acceptedFromCurrentExactScan++;
                    log.debug(
                            "[TARGET PROVENANCE] Marketplace listing {} accepted directly from CURRENT native Vinted model-filter scan. No post-filter target safety guard is applied.",
                            listing.listingId()
                    );
                } else {
                    deferredVintedModelOutsideCurrentScan++;
                    log.debug(
                            "[TARGET PROVENANCE] Marketplace listing {} is VINTED_MODEL backlog but is not present in the current exact native filter scan. Deferring it without title/URL/item-page target verification.",
                            listing.listingId()
                    );
                }
                continue;
            }

            /*
             * SEARCH_QUERY is the opposite mode: Vinted's text search is not
             * trusted as exact model identity, so the existing target safety
             * matcher and live item inspection remain mandatory.
             */
            ListingTargetAssessment catalogAssessment = listingTargetMatcher
                    .assessCatalogListing(listing, configuration);

            if (catalogAssessment == ListingTargetAssessment.MATCH) {
                eligibleListings.add(listing);
                matchedFromCatalogTitle++;
                continue;
            }

            if (catalogAssessment == ListingTargetAssessment.MISMATCH) {
                rejectedCatalogMismatch++;
                listingStatusUpdater.markTargetMismatch(botId, listing);
                persistedTargetMismatches++;
                continue;
            }

            ListingTargetAssessment urlAssessment = listingTargetMatcher
                    .assessListingUrl(listing, configuration);

            if (urlAssessment == ListingTargetAssessment.MATCH) {
                eligibleListings.add(listing);
                matchedFromUrlSlug++;
                continue;
            }

            if (urlAssessment == ListingTargetAssessment.MISMATCH) {
                rejectedUrlMismatch++;
                listingStatusUpdater.markTargetMismatch(botId, listing);
                persistedTargetMismatches++;
                continue;
            }

            boolean cached = listingDetailTargetInspector.hasCachedFullTitle(
                    listing.listingId()
            );

            if (cached) {
                try {
                    boolean cachedMatches = listingDetailTargetInspector
                            .matchesConfiguredTarget(listing, configuration);

                    if (cachedMatches) {
                        eligibleListings.add(listing);
                        matchedFromDetailCache++;
                    } else {
                        rejectedFromDetailCache++;
                        listingStatusUpdater.markTargetMismatch(botId, listing);
                        persistedTargetMismatches++;
                    }
                } catch (Exception exception) {
                    detailInspectionFailures++;
                    log.warn(
                            "[TARGET DETAIL] Cached identity for marketplace listing {} could not safely prove the target: {}. It remains DISCOVERED for retry.",
                            listing.listingId(),
                            NewNegotiationProcessor.getFriendlyErrorMessage(exception)
                    );
                }
                continue;
            }

            if (detailRequestsThisCycle >= MAX_DETAIL_INSPECTIONS_PER_CYCLE) {
                deferredByDetailLimit++;
                log.info(
                        "[TARGET DETAIL] Marketplace listing {} is persisted backlog with ambiguous identity, but the per-cycle detail limit ({}) is reached. It is deferred safely and cannot reach quota/submit this cycle.",
                        listing.listingId(),
                        MAX_DETAIL_INSPECTIONS_PER_CYCLE
                );
                continue;
            }

            if (!detailInspectionBudget.tryAcquire()) {
                deferredByGlobalDetailBudget++;
                log.info(
                        "[TARGET DETAIL] Marketplace listing {} needs a live detail inspection, but the shared catalog detail budget is exhausted ({}/{}). It remains DISCOVERED for a later catalog cycle and cannot reach quota/submit now.",
                        listing.listingId(),
                        detailInspectionBudget.used(),
                        detailInspectionBudget.limit()
                );
                continue;
            }

            if (detailRequestsThisCycle > 0) {
                context.getPage().waitForTimeout(DETAIL_INSPECTION_PACING_MS);
            }

            detailRequestsThisCycle++;

            try {
                boolean detailMatches = listingDetailTargetInspector
                        .matchesConfiguredTarget(listing, configuration);

                if (detailMatches) {
                    eligibleListings.add(listing);
                    matchedAfterDetailRequest++;
                } else {
                    rejectedAfterDetailRequest++;
                    listingStatusUpdater.markTargetMismatch(botId, listing);
                    persistedTargetMismatches++;
                }
            } catch (VintedRateLimitException exception) {
                log.warn(
                        "[RATE LIMIT] Vinted rate limit detected while inspecting marketplace listing {}. Stopping this work cycle immediately.",
                        listing.listingId()
                );
                throw exception;
            } catch (ListingUnavailableDuringVerificationException exception) {
                listingStatusUpdater.markUnavailable(botId, listing);
                persistedUnavailable++;
                log.info(
                        "[TARGET DETAIL] Marketplace listing {} became unavailable during target inspection and was persisted as UNAVAILABLE.",
                        listing.listingId()
                );
            } catch (Exception exception) {
                detailInspectionFailures++;
                log.warn(
                        "[TARGET DETAIL] Failed to inspect marketplace listing {}. It remains DISCOVERED and will be retried in a later cycle. Error: {}",
                        listing.listingId(),
                        NewNegotiationProcessor.getFriendlyErrorMessage(exception)
                );
                log.trace(
                        "[TARGET DETAIL] Full inspection error for marketplace listing {}.",
                        listing.listingId(),
                        exception
                );
            }
        }

        log.info(
                "[TARGET MATCHER] Checked {} price-eligible DISCOVERED candidates. Current native Vinted-filter accepted: {}, VINTED_MODEL backlog deferred outside current scan: {}, SEARCH_QUERY catalog matches: {}, URL matches: {}, detail-cache matches: {}, detail-request matches: {}, catalog mismatches: {}, URL mismatches: {}, detail-cache mismatches: {}, detail-request mismatches: {}, detail requests this cycle: {}/{}, detail failures: {}, deferred by detail limit: {}, deferred by shared catalog detail budget: {}, shared budget={}/{}, persisted target mismatches: {}, persisted unavailable: {}, final eligible: {}. Target mode: {}.",
                listings.size(),
                acceptedFromCurrentExactScan,
                deferredVintedModelOutsideCurrentScan,
                matchedFromCatalogTitle,
                matchedFromUrlSlug,
                matchedFromDetailCache,
                matchedAfterDetailRequest,
                rejectedCatalogMismatch,
                rejectedUrlMismatch,
                rejectedFromDetailCache,
                rejectedAfterDetailRequest,
                detailRequestsThisCycle,
                MAX_DETAIL_INSPECTIONS_PER_CYCLE,
                detailInspectionFailures,
                deferredByDetailLimit,
                deferredByGlobalDetailBudget,
                detailInspectionBudget.used(),
                detailInspectionBudget.limit(),
                persistedTargetMismatches,
                persistedUnavailable,
                eligibleListings.size(),
                configuration.getTargetMode()
        );

        return eligibleListings;
    }
}
