package com.arcticblu.subscriptioncapacity.web;

import com.arcticblu.subscriptioncapacity.TestcontainersConfiguration;
import com.arcticblu.subscriptioncapacity.domain.OptimizationRun;
import com.arcticblu.subscriptioncapacity.domain.SubscriptionRequest;
import com.arcticblu.subscriptioncapacity.repository.OptimizationRunRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.math.BigDecimal;
import java.time.Instant;
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

    @Autowired
    RestTestClient client;

    @Autowired
    OptimizationRunRepository runRepository;

    // The server serves requests on its own threads, so a test-managed transaction would
    // not cover them; state is cleared outright instead of rolled back.
    @BeforeEach
    void clearRuns() {
        runRepository.deleteAll();
    }

    @Test
    @DisplayName("the highest-value combination that fits the capacity is accepted")
    void acceptsTheBestFittingCombination() {
        OptimizationResultResponse response = optimize(assignmentExample());

        assertThat(response.acceptedSubscriptions())
                .extracting(SubscriptionRequestPayload::investorName)
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
                List.of(new SubscriptionRequestPayload("Investor A", new BigDecimal("5"), new BigDecimal("120")))));

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
                List.of(new SubscriptionRequestPayload("Investor A", new BigDecimal("5"), new BigDecimal("120")))));

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
                        new SubscriptionRequestPayload("Investor B", new BigDecimal("10.50"), new BigDecimal("200.25")))));

        assertThat(response.acceptedSubscriptions())
                .extracting(SubscriptionRequestPayload::requestedAmount)
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
                List.of(new SubscriptionRequestPayload("Investor A", new BigDecimal("5"), new BigDecimal("120")))));

        assertThat(errorFields(problem)).contains("maxCapacity");
    }

    @Test
    @DisplayName("a request offering no candidates is refused as a problem naming the candidate list")
    void refusesEmptyCandidateList() {
        Map<String, Object> problem = expectValidationFailure(
                new OptimizeRequest(new BigDecimal("15"), List.of()));

        assertThat(errorFields(problem)).contains("availableSubscriptions");
    }

    @Test
    @DisplayName("an amount finer than a minor unit is refused, pointing at the offending candidate")
    void refusesAmountFinerThanAMinorUnit() {
        Map<String, Object> problem = expectValidationFailure(new OptimizeRequest(
                new BigDecimal("15"),
                List.of(new SubscriptionRequestPayload("Investor A", new BigDecimal("5.123"), new BigDecimal("120")))));

        assertThat(errorFields(problem)).contains("availableSubscriptions[0].requestedAmount");
    }

    @Test
    @DisplayName("a candidate without an investor name is refused, pointing at the name field")
    void refusesBlankInvestorName() {
        Map<String, Object> problem = expectValidationFailure(new OptimizeRequest(
                new BigDecimal("15"),
                List.of(new SubscriptionRequestPayload("   ", new BigDecimal("5"), new BigDecimal("120")))));

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
                        new SubscriptionRequestPayload("Investor D", new BigDecimal("8"), new BigDecimal("160"))));
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
