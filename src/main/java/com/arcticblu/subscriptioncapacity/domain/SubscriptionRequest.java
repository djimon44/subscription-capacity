package com.arcticblu.subscriptioncapacity.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Table(name = "subscription_request")
public class SubscriptionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private OptimizationRun run;

    @Column(name = "investor_name", nullable = false, length = 255)
    private String investorName;

    @Column(name = "requested_amount", nullable = false)
    private BigDecimal requestedAmount;

    @Column(name = "fee_revenue", nullable = false)
    private BigDecimal feeRevenue;

    @Column(name = "accepted", nullable = false)
    private boolean accepted;

    @Column(name = "input_index", nullable = false)
    private int inputIndex;

    protected SubscriptionRequest() {
        // Required by JPA; not for application use.
    }

    public SubscriptionRequest(String investorName,
                               BigDecimal requestedAmount,
                               BigDecimal feeRevenue,
                               boolean accepted,
                               int inputIndex) {
        this.investorName = Objects.requireNonNull(investorName, "investorName must not be null");
        this.requestedAmount = CurrencyScale.normalize(
                Objects.requireNonNull(requestedAmount, "requestedAmount must not be null"));
        this.feeRevenue = CurrencyScale.normalize(
                Objects.requireNonNull(feeRevenue, "feeRevenue must not be null"));
        this.accepted = accepted;
        this.inputIndex = inputIndex;
    }

    void setRun(OptimizationRun run) {
        this.run = run;
    }

    public Long getId() {
        return id;
    }

    public OptimizationRun getRun() {
        return run;
    }

    public String getInvestorName() {
        return investorName;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public BigDecimal getFeeRevenue() {
        return feeRevenue;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public int getInputIndex() {
        return inputIndex;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SubscriptionRequest that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}