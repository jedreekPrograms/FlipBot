package pl.flipbot.playwright.negotiation;

import org.junit.Test;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.target.ListingDetailTargetInspector;
import pl.flipbot.playwright.target.VintedRateLimitException;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class NewNegotiationFinalCandidateVerifierTest {
    private final BotContext context = mock(BotContext.class);
    private final ListingStatusUpdater statuses = mock(ListingStatusUpdater.class);
    private final ListingDetailTargetInspector inspector = mock(ListingDetailTargetInspector.class);
    private final CatalogDetailInspectionBudget budget = new CatalogDetailInspectionBudget(2);
    private final NewNegotiationFinalCandidateVerifier verifier =
            new NewNegotiationFinalCandidateVerifier(context, statuses, inspector, budget);

    @Test
    public void exactCurrentModelScanIsFinalProofWithoutOpeningItemPages() {
        BotConfigurationDto config = config("VINTED_MODEL");
        ListingResponseDto candidate = listing("123");
        NewNegotiationFinalCandidateVerifier.Result result = verifier.verifyFinalCandidates(
                List.of(candidate), config, Set.of("123"), 20, 1);
        assertEquals(List.of(candidate), result.verifiedListings());
        assertEquals(1, result.checked());
        assertEquals(0, result.realItemPageRequests());
        assertEquals(0, budget.used());
        verifyNoInteractions(inspector, statuses);
    }

    @Test
    public void desiredVerifiedCountStopsBeforeInspectingUnneededCandidates() {
        BotConfigurationDto config = config("VINTED_MODEL");
        ListingResponseDto first = listing("123"), later = listing("456");
        NewNegotiationFinalCandidateVerifier.Result result = verifier.verifyFinalCandidates(
                List.of(first, later), config, Set.of("123", "456"), 20, 1);
        assertEquals(List.of(first), result.verifiedListings());
        assertEquals(1, result.checked());
        verifyNoInteractions(inspector, statuses);
    }

    @Test
    public void searchQueryUsesCachedDetailIdentityRatherThanCurrentScanPresence() {
        BotConfigurationDto config = config("SEARCH_QUERY");
        ListingResponseDto candidate = listing("123");
        when(inspector.hasCachedFullTitle("123")).thenReturn(true);
        when(inspector.matchesConfiguredTarget(candidate, config)).thenReturn(true);
        NewNegotiationFinalCandidateVerifier.Result result = verifier.verifyFinalCandidates(
                List.of(candidate), config, Set.of("123"), 20, 1);
        assertEquals(List.of(candidate), result.verifiedListings());
        assertEquals(0, result.realItemPageRequests());
        assertEquals(0, budget.used());
        verify(inspector).matchesConfiguredTarget(candidate, config);
    }

    @Test
    public void failedFinalInspectionPersistsMismatchAndDoesNotVerifyCandidate() {
        BotConfigurationDto config = config("SEARCH_QUERY");
        ListingResponseDto candidate = listing("123");
        bot(9L);
        when(inspector.hasCachedFullTitle("123")).thenReturn(true);
        NewNegotiationFinalCandidateVerifier.Result result = verifier.verifyFinalCandidates(
                List.of(candidate), config, Set.of("123"), 20, 1);
        assertTrue(result.verifiedListings().isEmpty());
        assertEquals(1, result.mismatches());
        verify(statuses).markTargetMismatch(9L, candidate);
    }

    @Test
    public void exhaustedSharedBudgetDefersLiveVerificationWithoutChangingStatus() {
        BotConfigurationDto config = config("SEARCH_QUERY");
        ListingResponseDto candidate = listing("123");
        assertTrue(budget.tryAcquire());
        assertTrue(budget.tryAcquire());
        NewNegotiationFinalCandidateVerifier.Result result = verifier.verifyFinalCandidates(
                List.of(candidate), config, Set.of("123"), 20, 1);
        assertTrue(result.verifiedListings().isEmpty());
        assertEquals(1, result.checked());
        assertEquals(0, result.realItemPageRequests());
        verify(inspector, never()).matchesConfiguredTarget(candidate, config);
        verifyNoInteractions(statuses);
    }

    @Test
    public void rateLimitRemainsFatalForCurrentCycle() {
        BotConfigurationDto config = config("SEARCH_QUERY");
        ListingResponseDto candidate = listing("123");
        when(inspector.hasCachedFullTitle("123")).thenReturn(true);
        when(inspector.matchesConfiguredTarget(candidate, config))
                .thenThrow(new VintedRateLimitException("rate limit"));
        assertThrows(VintedRateLimitException.class,
                () -> verifier.verifyFinalCandidates(
                        List.of(candidate), config, Set.of("123"), 20, 1));
        verifyNoInteractions(statuses);
    }

    private void bot(Long id) {
        BotDetailsDto bot = new BotDetailsDto();
        bot.setId(id);
        when(context.getBot()).thenReturn(bot);
    }

    private static BotConfigurationDto config(String mode) {
        BotConfigurationDto c = new BotConfigurationDto();
        c.setTargetMode(mode);
        return c;
    }

    private static ListingResponseDto listing(String listingId) {
        ListingResponseDto l = mock(ListingResponseDto.class);
        when(l.listingId()).thenReturn(listingId);
        return l;
    }
}
