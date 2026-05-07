package dev.marcelovitor.rinha.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TransactionData(
    @JsonProperty("amount")       double amount,
    @JsonProperty("installments") int    installments,
    @JsonProperty("requested_at") String requestedAt
) {}
