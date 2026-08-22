package com.arcticblu.subscriptioncapacity.domain;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "optimization_run")
public class OptimizationRun implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "max_capacity", nullable = false)
    private BigDecimal maxCapacity;

    @Column(name = "total_requested_amount", nullable = false)
    private BigDecimal totalRequestedAmount;

    @Column(name = "total_fee_revenue", nullable = false)
    private BigDecimal totalFeeRevenue;

    @Column(name = "accepted_count", nullable = false)
    private int acceptedCount;

    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;

    @Column(name = "algorithm_used", nullable = false, length = 32)
    private String algorithmUsed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("inputIndex ASC")
    private List<SubscriptionRequest> subscriptions = new ArrayList<>();

    /**
     * Tells {@code SimpleJpaRepository.save()} to {@code persist} rather than {@code merge}.
     *
     * <p>Its default {@code isNew()} check is {@code id == null}, but this entity assigns
     * its identifier in the constructor, so the id is never null and every save would
     * otherwise go through {@code merge()} — issuing a SELECT to look for an existing row
     * before each INSERT.
     */
    @Transient
    private boolean isNew = true;

    protected OptimizationRun() {
        // Required by JPA; not for application use.
    }

    public OptimizationRun(UUID id,
                           BigDecimal maxCapacity,
                           BigDecimal totalRequestedAmount,
                           BigDecimal totalFeeRevenue,
                           int acceptedCount,
                           int candidateCount,
                           String algorithmUsed,
                           Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.maxCapacity = CurrencyScale.normalize(
                Objects.requireNonNull(maxCapacity, "maxCapacity must not be null"));
        this.totalRequestedAmount = CurrencyScale.normalize(
                Objects.requireNonNull(totalRequestedAmount, "totalRequestedAmount must not be null"));
        this.totalFeeRevenue = CurrencyScale.normalize(
                Objects.requireNonNull(totalFeeRevenue, "totalFeeRevenue must not be null"));

        if (acceptedCount < 0) {
            throw new IllegalArgumentException("acceptedCount must not be negative: " + acceptedCount);
        }
        if (candidateCount < 0) {
            throw new IllegalArgumentException("candidateCount must not be negative: " + candidateCount);
        }
        if (acceptedCount > candidateCount) {
            throw new IllegalArgumentException(
                    "acceptedCount must not exceed candidateCount: %d > %d".formatted(acceptedCount, candidateCount));
        }
        this.acceptedCount = acceptedCount;
        this.candidateCount = candidateCount;
        this.algorithmUsed = Objects.requireNonNull(algorithmUsed, "algorithmUsed must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /** Adds a subscription and keeps both sides of the association consistent. */
    public void addSubscription(SubscriptionRequest subscription) {
        subscriptions.add(subscription);
        subscription.setRun(this);
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public BigDecimal getMaxCapacity() {
        return maxCapacity;
    }

    public BigDecimal getTotalRequestedAmount() {
        return totalRequestedAmount;
    }

    public BigDecimal getTotalFeeRevenue() {
        return totalFeeRevenue;
    }

    public int getAcceptedCount() {
        return acceptedCount;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public String getAlgorithmUsed() {
        return algorithmUsed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Wraps rather than copies: {@code List.copyOf} would iterate the collection and so
     * force a lazily-loaded association to initialize on every call, including a bare
     * {@code size()}. The wrapper still prevents callers from bypassing
     * {@link #addSubscription}.
     */
    public List<SubscriptionRequest> getSubscriptions() {
        return Collections.unmodifiableList(subscriptions);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OptimizationRun that)) {
            return false;
        }
        // Hibernate instantiates entities through the no-arg constructor and populates
        // fields afterwards; id is null for the duration of that window.
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        // Constant rather than id.hashCode(): SubscriptionRequest genuinely requires it,
        // because its database-generated id changes from null to a value on flush and the
        // hash must not change with it. Both entities use the same pattern so the rule
        // holds uniformly across the model. Neither is ever held in a large hash-based
        // collection, so sharing one bucket costs nothing in practice.
        return getClass().hashCode();
    }
}
