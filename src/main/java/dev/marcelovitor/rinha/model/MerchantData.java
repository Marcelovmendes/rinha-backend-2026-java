package dev.marcelovitor.rinha.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MerchantData(
    @JsonProperty("id")         String id,
    @JsonProperty("mcc")        String mcc,
    @JsonProperty("avg_amount") double avgAmount
) {}
