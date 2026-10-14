package pl.flipbot.playwright.negotiation;

import org.junit.Test;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.target.ListingDetailTargetInspector;
import pl.flipbot.playwright.target.ListingTargetAssessment;
import pl.flipbot.playwright.target.ListingTargetMatcher;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class NewNegotiationTargetEligibilityTest {
    private final BotContext context = mock(BotContext.class);
    private final ListingStatusUpdater statusUpdater = mock(ListingStatusUpdater.class);
    private final ListingTargetMatcher matcher = mock(ListingTargetMatcher.class);
    private final ListingDetailTargetInspector inspector = mock(ListingDetailTargetInspector.class);
    private final CatalogDetailInspectionBudget sharedBudget = new CatalogDetailInspectionBudget(2);
    private final NewNegotiationTargetEligibility eligibility =
            new NewNegotiationTargetEligibility(context, statusUpdater, matcher, inspector, sharedBudget);

    @Test
    public void exactModelOnlyAcceptsCurrentNativeScanAndNeverInspectsSellerText() {
        BotConfigurationDto configuration = configuration("VINTED_MODEL");
        ListingResponseDto current = listing("123", "Wrong-looking seller title: Galaxy S24");
        ListingResponseDto stale = listing("456", "Galaxy S25");
        givenBot();

        assertEquals(List.of(current), eligibility.retainTargetEligibleListings(
                List.of(current, stale), configuration, Set.of("123")));
        assertEquals(0, sharedBudget.used());
        verifyNoInteractions(inspector, matcher, statusUpdater);
    }

    @Test
    public void missingCurrentScanDefersAllExactModelBacklogWithoutQuotaOrDetailUsage() {
        BotConfigurationDto configuration = configuration("VINTED_MODEL");
        givenBot();
        assertTrue(eligibility.retainTargetEligibleListings(
                List.of(listing("123", "Galaxy S25")), configuration, Set.of()).isEmpty());
        assertEquals(0, sharedBudget.used());
        verifyNoInteractions(inspector, matcher, statusUpdater);
    }

    @Test
    public void searchQueryMustNotUseCurrentScanAsProofWhenCatalogMismatch() {
        BotConfigurationDto config = configuration("SEARCH_QUERY");
        ListingResponseDto candidate = listing("123", "Samsung Galaxy S25 Ultra");
        givenBot();
        when(matcher.assessCatalogListing(candidate, config)).thenReturn(ListingTargetAssessment.MISMATCH);

        assertTrue(eligibility.retainTargetEligibleListings(
                List.of(candidate), config, Set.of("123")).isEmpty());
        verify(statusUpdater).markTargetMismatch(9L, candidate);
        verifyNoInteractions(inspector);
        assertEquals(0, sharedBudget.used());
    }

    @Test
    public void searchQueryCatalogMatchIsAcceptedWithoutLiveDetailInspection() {
        BotConfigurationDto config = configuration("SEARCH_QUERY");
        ListingResponseDto candidate = listing("123", "Galaxy S25 FE");
        givenBot();
        when(matcher.assessCatalogListing(candidate, config)).thenReturn(ListingTargetAssessment.MATCH);

        assertEquals(List.of(candidate), eligibility.retainTargetEligibleListings(
                List.of(candidate), config, Set.of("123")));
        verifyNoInteractions(inspector);
        assertEquals(0, sharedBudget.used());
    }

    @Test
    public void unknownSearchCandidateWithNoSharedBudgetIsSafelyDeferred() {
        BotConfigurationDto config = configuration("SEARCH_QUERY");
        ListingResponseDto candidate = listing("123", "Unknown phone");
        givenBot();
        CatalogDetailInspectionBudget emptyBudget = new CatalogDetailInspectionBudget(1);
        assertTrue(emptyBudget.tryAcquire());
        NewNegotiationTargetEligibility gated = new NewNegotiationTargetEligibility(
                context, statusUpdater, matcher, inspector, emptyBudget);

        assertTrue(gated.retainTargetEligibleListings(
                List.of(candidate), config, Set.of("123")).isEmpty());
        verify(inspector).hasCachedFullTitle("123");
        verify(inspector, never()).matchesConfiguredTarget(candidate, config);
        verifyNoInteractions(statusUpdater);
        assertEquals(1, emptyBudget.used());
    }

    private void givenBot() {
        BotDetailsDto bot = new BotDetailsDto();
        bot.setId(9L);
        when(context.getBot()).thenReturn(bot);
    }

    private static BotConfigurationDto configuration(String mode) {
        BotConfigurationDto c = new BotConfigurationDto();
        c.setTargetMode(mode);
        return c;
    }

    private static ListingResponseDto listing(String id, String title) {
        ListingResponseDto candidate = mock(ListingResponseDto.class);
        when(candidate.listingId()).thenReturn(id);
        when(candidate.title()).thenReturn(title);
        return candidate;
    }
}
