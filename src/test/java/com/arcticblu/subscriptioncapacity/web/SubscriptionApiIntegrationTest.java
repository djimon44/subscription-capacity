package com.arcticblu.subscriptioncapacity.web;

import com.arcticblu.subscriptioncapacity.TestcontainersConfiguration;
import com.arcticblu.subscriptioncapacity.domain.OptimizationRun;
import com.arcticblu.subscriptioncapacity.domain.SubscriptionRequest;
import com.arcticblu.subscriptioncapacity.repository.OptimizationRunRepository;
import com.arcticblu.subscriptioncapacity.web.dto.AcceptedSubscription;
import com.arcticblu.subscriptioncapacity.web.dto.OptimizationResultResponse;
import com.arcticblu.subscriptioncapacity.web.dto.OptimizationRunSummary;
import com.arcticblu.subscriptioncapacity.web.dto.OptimizeRequest;
import com.arcticblu.subscriptioncapacity.web.dto.PagedResponse;
import com.arcticblu.subscriptioncapacity.web.dto.SubscriptionRequestPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class SubscriptionApiIntegrationTest {

    private static final String OPTIMIZE_PATH = "/api/v1/subscriptions/optimize";
    private static final String RUN_PATH = "/api/v1/subscriptions/{requestId}";
    private static final String LISTING_PATH = "/api/v1/subscriptions";

    // An hour apart, so no clock resolution or tie-break can reorder them.
    private static final Instant TEN_O_CLOCK = Instant.parse("2026-06-01T10:00:00Z");
    private static final Instant ELEVEN_O_CLOCK = Instant.parse("2026-06-01T11:00:00Z");
    private static final Instant TWELVE_O_CLOCK = Instant.parse("2026-06-01T12:00:00Z");

    private static final ParameterizedTypeReference<Map<String, Object>> PROBLEM =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<PagedResponse<OptimizationRunSummary>> LISTING =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<Map<String, Object>> RESULT_AS_MAP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    RestTestClient client;

    @Autowired
    OptimizationRunRepository runRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearRuns() {
        jdbcTemplate.update("UPDATE subscription_request SET carried_from_id = NULL WHERE carried_from_id IS NOT NULL");
        runRepository.deleteAll();
    }

    @Test
    @DisplayName("the highest-value combination that fits the capacity is accepted")
    void acceptsTheBestFittingCombination() {
        OptimizationResultResponse response = optimize(assignmentExample());

        assertThat(response.acceptedSubscriptions())
                .extracting(AcceptedSubscription::investorName)
                .containsExactly("Investor A", "Investor B");
        assertThat(response.totalRequestedAmount()).isEqualByComparingTo("15.00");
        assertThat(response.totalFeeRevenue()).isEqualByComparingTo("320.00");
    }

    @Test
    @DisplayName("a created run advertises where it can be read back from")
    void advertisesTheLocationOfTheCreatedRun() {
        var result = client.post().uri(OPTIMIZE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(assignmentExample())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OptimizationResultResponse.class)
                .returnResult();

        UUID requestId = result.getResponseBody().requestId();

        assertThat(result.getResponseHeaders().getLocation())
                .hasToString("http://localhost:%d/api/v1/subscriptions/%s"
                        .formatted(result.getUrl().getPort(), requestId));
    }

    @Test
    @DisplayName("a whole-number amount comes back with both decimal places")
    void rendersAmountsAtTwoDecimalPlaces() {
        OptimizationResultResponse response = optimize(new OptimizeRequest(
                new BigDecimal("10"),
                List.of(new SubscriptionRequestPayload("Investor A", new BigDecimal("5"), new BigDecimal("120"))),
                true));

        assertThat(response.acceptedSubscriptions().getFirst().requestedAmount().toPlainString())
                .isEqualTo("5.00");
        assertThat(response.totalRequestedAmount().toPlainString()).isEqualTo("5.00");
        assertThat(response.totalFeeRevenue().toPlainString()).isEqualTo("120.00");
    }

    @Test
    @DisplayName("reading a run back reproduces the response the create returned")
    void readsBackExactlyWhatTheCreateReturned() {
        OptimizationResultResponse created = optimize(assignmentExample());

        OptimizationResultResponse reloaded = client.get().uri(RUN_PATH, created.requestId())
                .exchange()
                .expectStatus().isOk()
                .expectBody(OptimizationResultResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(reloaded).isEqualTo(created);
        assertThat(reloaded.createdAt()).isEqualTo(created.createdAt());
    }

    @Test
    @DisplayName("declined candidates are kept in the audit trail alongside the accepted ones")
    void persistsDeclinedCandidatesToo() {
        OptimizationResultResponse created = optimize(assignmentExample());

        OptimizationRun run = runRepository.findByIdWithSubscriptions(created.requestId()).orElseThrow();

        assertThat(run.getSubscriptions()).hasSize(4);
        assertThat(run.getSubscriptions()).filteredOn(SubscriptionRequest::isAccepted)
                .extracting(SubscriptionRequest::getInvestorName)
                .containsExactly("Investor A", "Investor B");
        assertThat(run.getSubscriptions()).filteredOn(subscription -> !subscription.isAccepted())
                .extracting(SubscriptionRequest::getInvestorName)
                .containsExactly("Investor C", "Investor D");
    }

    @Test
    @DisplayName("a run in which no candidate fits succeeds with nothing accepted")
    void succeedsWithNothingAcceptedWhenNothingFits() {
        OptimizationResultResponse response = optimize(new OptimizeRequest(
                new BigDecimal("1"),
                List.of(new SubscriptionRequestPayload("Investor A", new BigDecimal("5"), new BigDecimal("120"))),
                true));

        assertThat(response.acceptedSubscriptions()).isEmpty();
        assertThat(response.totalRequestedAmount()).isEqualByComparingTo("0.00");
        assertThat(response.totalFeeRevenue()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("amounts with hundredths come back unchanged")
    void keepsFractionalAmountsIntact() {
        OptimizationResultResponse response = optimize(new OptimizeRequest(
                new BigDecimal("15.75"),
                List.of(
                        new SubscriptionRequestPayload("Investor A", new BigDecimal("5.25"), new BigDecimal("120.50")),
                        new SubscriptionRequestPayload("Investor B", new BigDecimal("10.50"), new BigDecimal("200.25"))),
                true));

        assertThat(response.acceptedSubscriptions())
                .extracting(AcceptedSubscription::requestedAmount)
                .extracting(BigDecimal::toPlainString)
                .containsExactly("5.25", "10.50");
        assertThat(response.totalRequestedAmount()).isEqualByComparingTo("15.75");
        assertThat(response.totalFeeRevenue()).isEqualByComparingTo("320.75");
    }

    @Test
    @DisplayName("a negative capacity is refused as a problem naming the capacity field")
    void refusesNegativeCapacity() {
        Map<String, Object> problem = expectValidationFailure(new OptimizeRequest(
                new BigDecimal("-1"),
                List.of(new SubscriptionRequestPayload("Investor A", new BigDecimal("5"), new BigDecimal("120"))),
                true));

        assertThat(errorFields(problem)).contains("maxCapacity");
    }

    @Test
    @DisplayName("a request offering no candidates is refused as a problem naming the candidate list")
    void refusesEmptyCandidateList() {
        Map<String, Object> problem = expectValidationFailure(
                new OptimizeRequest(new BigDecimal("15"), List.of(), true));

        assertThat(errorFields(problem)).contains("availableSubscriptions");
    }

    @Test
    @DisplayName("an amount finer than a minor unit is refused, pointing at the offending candidate")
    void refusesAmountFinerThanAMinorUnit() {
        Map<String, Object> problem = expectValidationFailure(new OptimizeRequest(
                new BigDecimal("15"),
                List.of(new SubscriptionRequestPayload("Investor A", new BigDecimal("5.123"), new BigDecimal("120"))),
                true));

        assertThat(errorFields(problem)).contains("availableSubscriptions[0].requestedAmount");
    }

    @Test
    @DisplayName("a candidate without an investor name is refused, pointing at the name field")
    void refusesBlankInvestorName() {
        Map<String, Object> problem = expectValidationFailure(new OptimizeRequest(
                new BigDecimal("15"),
                List.of(new SubscriptionRequestPayload("   ", new BigDecimal("5"), new BigDecimal("120"))),
                true));

        assertThat(errorFields(problem)).contains("availableSubscriptions[0].investorName");
    }

    @Test
    @DisplayName("a body that is not valid JSON is refused as a malformed body")
    void refusesMalformedJson() {
        Map<String, Object> problem = client.post().uri(OPTIMIZE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"maxCapacity\": 15, \"availableSubscriptions\": [")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(PROBLEM)
                .returnResult()
                .getResponseBody();

        assertThat(problem).containsEntry("title", "Malformed request body");
    }

    @Test
    @DisplayName("asking for a run that was never created reports it as not found")
    void reportsUnknownRunAsNotFound() {
        Map<String, Object> problem = client.get().uri(RUN_PATH, UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(PROBLEM)
                .returnResult()
                .getResponseBody();

        assertThat(problem).containsEntry("title", "Optimization run not found");
    }

    @Test
    @DisplayName("an identifier that is not a UUID is refused rather than looked up")
    void refusesIdentifierThatIsNotAUuid() {
        client.get().uri(RUN_PATH, "not-a-uuid")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("the audit listing reports runs newest first")
    void listsRunsNewestFirst() {
        storeRun(TEN_O_CLOCK, new BigDecimal("10"));
        storeRun(ELEVEN_O_CLOCK, new BigDecimal("20"));
        storeRun(TWELVE_O_CLOCK, new BigDecimal("30"));

        PagedResponse<OptimizationRunSummary> listing = listing(LISTING_PATH);

        assertThat(listing.totalElements()).isEqualTo(3L);
        assertThat(listing.content())
                .extracting(OptimizationRunSummary::maxCapacity)
                .extracting(BigDecimal::toPlainString)
                .containsExactly("30.00", "20.00", "10.00");
        assertThat(listing.content())
                .extracting(OptimizationRunSummary::createdAt)
                .containsExactly(TWELVE_O_CLOCK, ELEVEN_O_CLOCK, TEN_O_CLOCK);
    }

    @Test
    @DisplayName("a listing entry reports how many candidates applied as well as how many were accepted")
    void listingReportsBothCounts() {
        optimize(assignmentExample());

        OptimizationRunSummary summary = listing(LISTING_PATH).content().getFirst();

        assertThat(summary.acceptedCount()).isEqualTo(2);
        assertThat(summary.candidateCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("a page size beyond the configured maximum is capped rather than honoured")
    void capsOversizedPageRequests() {
        optimize(assignmentExample());

        assertThat(listing(LISTING_PATH + "?size=500").size()).isEqualTo(100);
    }

    @Test
    @DisplayName("the audit listing slices its results into pages")
    void slicesResultsIntoPages() {
        storeRun(TEN_O_CLOCK, new BigDecimal("10"));
        storeRun(ELEVEN_O_CLOCK, new BigDecimal("20"));
        storeRun(TWELVE_O_CLOCK, new BigDecimal("30"));

        PagedResponse<OptimizationRunSummary> firstPage = listing(LISTING_PATH + "?page=0&size=2");

        assertThat(firstPage.content()).hasSize(2);
        assertThat(firstPage.page()).isZero();
        assertThat(firstPage.size()).isEqualTo(2);
        assertThat(firstPage.totalElements()).isEqualTo(3L);
        assertThat(firstPage.totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("an unrecognised sort value is ignored rather than failing the listing")
    void ignoresUnrecognisedSortValue() {
        storeRun(TEN_O_CLOCK, new BigDecimal("10"));
        storeRun(ELEVEN_O_CLOCK, new BigDecimal("20"));
        storeRun(TWELVE_O_CLOCK, new BigDecimal("30"));

        // Passed through to Spring Data this would raise PropertyReferenceException and
        // surface as a 500, which makes a caller-supplied string a denial of service.
        PagedResponse<OptimizationRunSummary> listing = listing(LISTING_PATH + "?sort=doesNotExist");

        assertThat(listing.content())
                .extracting(OptimizationRunSummary::createdAt)
                .containsExactly(TWELVE_O_CLOCK, ELEVEN_O_CLOCK, TEN_O_CLOCK);
    }

    // --- carry-forward ---------------------------------------------------------------

    @Test
    @DisplayName("carry-forward eligibility: declined-once, carried-and-accepted, and carried-and-declined-again each behave as documented")
    void carryForwardEligibilityFollowsTheDeclinedAndNotYetCopiedRule() {
        OptimizationResultResponse run1 = optimize(assignmentExample());
        // capacity 15: accepts A and B; declines C(3, 80) and D(8, 160).
        assertThat(run1.acceptedSubscriptions()).extracting(AcceptedSubscription::investorName)
                .containsExactly("Investor A", "Investor B");

        OptimizationResultResponse run2 = optimize(new OptimizeRequest(
                new BigDecimal("3"),
                List.of(new SubscriptionRequestPayload("Investor Y", new BigDecimal("100"), new BigDecimal("1"))),
                true));
        // Pool: Y(100, 1) new, C(3, 80) carried from run1, D(8, 160) carried from run1.
        // Capacity 3 admits only C; Y and D are both declined here too. D is now
        // persisted as a *copy* in run2, declined, pointing back at run1's D row.
        assertThat(run2.acceptedSubscriptions()).extracting(AcceptedSubscription::investorName)
                .containsExactly("Investor C");
        AcceptedSubscription carriedC = run2.acceptedSubscriptions().getFirst();
        assertThat(carriedC.carriedForward()).isTrue();
        assertThat(carriedC.originalRequestId()).isEqualTo(run1.requestId());

        OptimizationResultResponse run3 = optimize(new OptimizeRequest(
                new BigDecimal("200"),
                List.of(new SubscriptionRequestPayload("Investor Z", new BigDecimal("1"), new BigDecimal("1"))),
                true));
        // Capacity 200 comfortably exceeds every candidate still eligible at this point,
        // so every eligible candidate is accepted and an absence is evidence that a
        // candidate was not offered at all.
        assertThat(run3.acceptedSubscriptions()).extracting(AcceptedSubscription::investorName)
                .containsExactlyInAnyOrder("Investor Z", "Investor Y", "Investor D");

        // C was carried into run2 and accepted there: run1's original C row now has a
        // copy (excluded), and the copy itself is accepted, not declined (excluded too).
        assertThat(run3.acceptedSubscriptions()).extracting(AcceptedSubscription::investorName)
                .doesNotContain("Investor C");

        // D was carried into run2 and declined again there, so it is run2's *copy* of D
        // -- not run1's original row -- that is eligible and offered to run3.
        AcceptedSubscription carriedD = run3.acceptedSubscriptions().stream()
                .filter(a -> a.investorName().equals("Investor D"))
                .findFirst().orElseThrow();
        assertThat(carriedD.carriedForward()).isTrue();
        assertThat(carriedD.originalRequestId()).isEqualTo(run2.requestId());

        // Y was a new candidate in run2, declined there and never carried before now, so
        // this is its first carry: eligible in its own right, per the same rule as C and
        // D were after run1.
        AcceptedSubscription carriedY = run3.acceptedSubscriptions().stream()
                .filter(a -> a.investorName().equals("Investor Y"))
                .findFirst().orElseThrow();
        assertThat(carriedY.carriedForward()).isTrue();
        assertThat(carriedY.originalRequestId()).isEqualTo(run2.requestId());

        AcceptedSubscription newZ = run3.acceptedSubscriptions().stream()
                .filter(a -> a.investorName().equals("Investor Z"))
                .findFirst().orElseThrow();
        assertThat(newZ.carriedForward()).isFalse();
        assertThat(newZ.originalRequestId()).isNull();
    }

    @Test
    @DisplayName("the JSON omits originalRequestId for a newly submitted candidate but carries it for a carried one")
    void omitsOriginalRequestIdInJsonForNewCandidatesOnly() {
        optimize(assignmentExample());
        // declines Investor C (3, 80) and Investor D (8, 160).

        Map<String, Object> body = client.post().uri(OPTIMIZE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OptimizeRequest(
                        new BigDecimal("13"),
                        List.of(new SubscriptionRequestPayload("Investor Y", new BigDecimal("10"), new BigDecimal("500"))),
                        true))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(RESULT_AS_MAP)
                .returnResult()
                .getResponseBody();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> accepted = (List<Map<String, Object>>) body.get("acceptedSubscriptions");

        Map<String, Object> newEntry = accepted.stream()
                .filter(entry -> entry.get("investorName").equals("Investor Y"))
                .findFirst().orElseThrow();
        assertThat(newEntry).containsEntry("carriedForward", false);
        assertThat(newEntry).doesNotContainKey("originalRequestId");

        Map<String, Object> carriedEntry = accepted.stream()
                .filter(entry -> entry.get("investorName").equals("Investor C"))
                .findFirst().orElseThrow();
        assertThat(carriedEntry).containsEntry("carriedForward", true);
        assertThat(carriedEntry).containsKey("originalRequestId");
    }

    @Test
    @DisplayName("the original run's declined row is unchanged after it is carried forward into a later run")
    void originalRunIsUnchangedAfterOneOfItsDeclinedCandidatesIsCarriedForward() {
        OptimizationResultResponse run1 = optimize(assignmentExample());

        optimize(new OptimizeRequest(
                new BigDecimal("3"),
                List.of(new SubscriptionRequestPayload("Investor Y", new BigDecimal("100"), new BigDecimal("1"))),
                true));
        // Carries Investor C forward from run1 and accepts it there.

        OptimizationRun reloadedRun1 = runRepository.findByIdWithSubscriptions(run1.requestId()).orElseThrow();

        assertThat(reloadedRun1.getTotalRequestedAmount()).isEqualByComparingTo(run1.totalRequestedAmount());
        assertThat(reloadedRun1.getTotalFeeRevenue()).isEqualByComparingTo(run1.totalFeeRevenue());
        assertThat(reloadedRun1.getAcceptedCount()).isEqualTo(2);
        assertThat(reloadedRun1.getCandidateCount()).isEqualTo(4);

        SubscriptionRequest originalC = reloadedRun1.getSubscriptions().stream()
                .filter(subscription -> subscription.getInvestorName().equals("Investor C"))
                .findFirst().orElseThrow();
        assertThat(originalC.isAccepted()).isFalse();
    }

    @Test
    @DisplayName("max-carried-forward-candidates bounds the pool even when more declined candidates are eligible")
    void boundsThePoolAtTheConfiguredCarryForwardLimit() {
        List<SubscriptionRequestPayload> manyDeclined = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            manyDeclined.add(new SubscriptionRequestPayload("Investor " + i, BigDecimal.ONE, BigDecimal.ONE));
        }
        // capacity 0 declines every one of the 105 candidates.
        optimize(new OptimizeRequest(BigDecimal.ZERO, manyDeclined, true));

        OptimizationResultResponse run2 = optimize(new OptimizeRequest(
                BigDecimal.ZERO,
                List.of(new SubscriptionRequestPayload("Investor New", BigDecimal.ONE, BigDecimal.ONE)),
                true));

        OptimizationRunSummary summary = listing(LISTING_PATH).content().stream()
                .filter(s -> s.requestId().equals(run2.requestId()))
                .findFirst().orElseThrow();

        // 1 new candidate plus the configured ceiling of 100 carried-forward candidates
        // (application.yml: max-carried-forward-candidates: 100), not all 105 that were
        // actually eligible.
        assertThat(summary.candidateCount()).isEqualTo(101);
    }

    @Test
    @DisplayName("the two-request worked example: run 2 weighs its whole pool, new and carried forward together")
    void weighsTheWholePoolOnTheTwoRequestWorkedExample() {
        OptimizationResultResponse run1 = optimize(assignmentExample());
        // Run 1, capacity 15: accepts A and B, declines C(3, 80) and D(8, 160).
        assertThat(run1.totalFeeRevenue()).isEqualByComparingTo("320.00");

        OptimizationResultResponse run2 = optimize(new OptimizeRequest(
                new BigDecimal("22"),
                List.of(
                        new SubscriptionRequestPayload("Investor A1", new BigDecimal("5"), new BigDecimal("120")),
                        new SubscriptionRequestPayload("Investor B2", new BigDecimal("10"), new BigDecimal("200")),
                        new SubscriptionRequestPayload("Investor C2", new BigDecimal("8"), new BigDecimal("160"))),
                true));
        // Run 2, capacity 22, pool: A1(5, 120), B2(10, 200), C2(8, 160) new, plus C(3, 80)
        // and D(8, 160) carried from run 1. A1+B2+C2 alone is 23, over capacity. B2+C2+C
        // is 21 for 440 and fits. Deliberately not asserting a specific expected selection
        // here -- the solver decides, and this pins only what must be true of whatever it
        // returns: the capacity constraint holds, the reported totals are internally
        // consistent with the accepted list, and the result is at least as good as the
        // known-feasible B2+C2+C combination.
        BigDecimal sumOfAcceptedAmounts = run2.acceptedSubscriptions().stream()
                .map(AcceptedSubscription::requestedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumOfAcceptedFees = run2.acceptedSubscriptions().stream()
                .map(AcceptedSubscription::feeRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(run2.totalRequestedAmount()).isEqualByComparingTo(sumOfAcceptedAmounts);
        assertThat(run2.totalFeeRevenue()).isEqualByComparingTo(sumOfAcceptedFees);
        assertThat(run2.totalRequestedAmount()).isLessThanOrEqualTo(new BigDecimal("22"));
        assertThat(run2.totalFeeRevenue()).isGreaterThanOrEqualTo(new BigDecimal("440"));
    }

    // --- dispatch failures ---------------------------------------------------------

    @Test
    @DisplayName("an unknown path is reported as not found rather than as an internal error")
    void reportsUnknownPathAsNotFound() {
        client.get().uri("/api/v1/no-such-resource")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("a method the collection does not support is reported as method not allowed")
    void reportsUnsupportedMethodAsMethodNotAllowed() {
        client.delete().uri(LISTING_PATH)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("a body in a content type the endpoint cannot read is reported as unsupported media type")
    void reportsUnsupportedContentTypeAsUnsupportedMediaType() {
        client.post().uri(OPTIMIZE_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("maxCapacity=15")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // --- helpers -------------------------------------------------------------------

    private OptimizationResultResponse optimize(OptimizeRequest request) {
        return client.post().uri(OPTIMIZE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OptimizationResultResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private PagedResponse<OptimizationRunSummary> listing(String uri) {
        return client.get().uri(uri)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LISTING)
                .returnResult()
                .getResponseBody();
    }

    private Map<String, Object> expectValidationFailure(OptimizeRequest request) {
        return client.post().uri(OPTIMIZE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(PROBLEM)
                .returnResult()
                .getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static List<String> errorFields(Map<String, Object> problem) {
        return ((List<Map<String, String>>) problem.get("errors")).stream()
                .map(error -> error.get("field"))
                .toList();
    }

    private static OptimizeRequest assignmentExample() {
        return new OptimizeRequest(
                new BigDecimal("15"),
                List.of(
                        new SubscriptionRequestPayload("Investor A", new BigDecimal("5"), new BigDecimal("120")),
                        new SubscriptionRequestPayload("Investor B", new BigDecimal("10"), new BigDecimal("200")),
                        new SubscriptionRequestPayload("Investor C", new BigDecimal("3"), new BigDecimal("80")),
                        new SubscriptionRequestPayload("Investor D", new BigDecimal("8"), new BigDecimal("160"))),
                true);
    }

    /**
     * Stores a run directly, bypassing the create endpoint.
     *
     * <p>The listing tests are about ordering and slicing, and the API cannot express the
     * timestamp a run is created with: it comes from a clock truncated to milliseconds,
     * and the {@code created_at DESC, id DESC} tie-break falls to a random UUID, so three
     * runs created in quick succession order arbitrarily. Writing the timestamps here
     * makes the expected order a fact of the fixture rather than of how fast the machine
     * happens to be. The create path itself is exercised through the API by the cases
     * above.
     *
     * <p>Totals, counts and the algorithm name are placeholders: nothing in these two
     * cases reads them.
     */
    private void storeRun(Instant createdAt, BigDecimal maxCapacity) {
        runRepository.save(new OptimizationRun(
                UUID.randomUUID(),
                maxCapacity,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                0,
                "TEST_FIXTURE",
                createdAt));
    }
}
