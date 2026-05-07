package dev.marcelovitor.rinha.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record CustomerData(
    @JsonProperty("avg_amount")       double       avgAmount,
    @JsonProperty("tx_count_24h")     int          txCount24h,
    @JsonProperty("known_merchants")  List<String> knownMerchants
) {}
