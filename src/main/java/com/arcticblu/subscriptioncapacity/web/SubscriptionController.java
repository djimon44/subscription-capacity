package com.arcticblu.subscriptioncapacity.web;

import com.arcticblu.subscriptioncapacity.service.SubscriptionOptimizationService;
import com.arcticblu.subscriptioncapacity.web.dto.OptimizationResultResponse;
import com.arcticblu.subscriptioncapacity.web.dto.OptimizationRunSummary;
import com.arcticblu.subscriptioncapacity.web.dto.OptimizeRequest;
import com.arcticblu.subscriptioncapacity.web.dto.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private final SubscriptionOptimizationService optimizationService;

    public SubscriptionController(SubscriptionOptimizationService optimizationService) {
        this.optimizationService = optimizationService;
    }

    /**
     * Runs the allocation for a funding window and persists both the request and
     * its outcome.
     *
     * <p>An input for which no combination fits within the capacity is a successful
     * run with nothing accepted, not an error, so it also returns 201 with an empty
     * list and zero totals.
     */
    @PostMapping("/optimize")
    public ResponseEntity<OptimizationResultResponse> optimize(
            @Valid @RequestBody OptimizeRequest request,
            UriComponentsBuilder uriBuilder) {

        OptimizationResultResponse result = optimizationService.optimize(request);

        URI location = uriBuilder
                .path("/api/v1/subscriptions/{requestId}")
                .buildAndExpand(result.requestId())
                .toUri();

        return ResponseEntity.created(location).body(result);
    }

    /** Returns a previously persisted optimization result. */
    @GetMapping("/{requestId}")
    public OptimizationResultResponse findByRequestId(@PathVariable UUID requestId) {
        return optimizationService.findByRequestId(requestId);
    }

    /**
     * Returns the audit trail of past runs.
     *
     * <p>The ordering is fixed: newest first, by creation time and then by identifier.
     * A {@code sort} query parameter is accepted for compatibility with the standard
     * pageable binding but is <em>not</em> honoured, and the resolved sort is discarded
     * here rather than passed on. Forwarding it would achieve nothing useful and one
     * concrete harm: the repository query already names its own ordering, so a
     * recognised property such as {@code createdAt,asc} would be silently ignored,
     * while an unrecognised one would reach Spring Data and fail with a
     * {@code PropertyReferenceException} - a caller-supplied string turning a read into
     * a 500. Only the page number and page size survive.
     */
    @GetMapping
    public PagedResponse<OptimizationRunSummary> findAll(Pageable pageable) {
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return optimizationService.findAll(unsorted);
    }
}