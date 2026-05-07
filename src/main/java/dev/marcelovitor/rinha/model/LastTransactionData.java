package dev.marcelovitor.rinha.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LastTransactionData(
    @JsonProperty("timestamp")        String timestamp,
    @JsonProperty("km_from_current")  double kmFromCurrent
) {}
