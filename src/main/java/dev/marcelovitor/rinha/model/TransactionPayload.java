package dev.marcelovitor.rinha.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TransactionPayload(
    @JsonProperty("id")               String              id,
    @JsonProperty("transaction")      TransactionData     transaction,
    @JsonProperty("customer")         CustomerData        customer,
    @JsonProperty("merchant")         MerchantData        merchant,
    @JsonProperty("terminal")         TerminalData        terminal,
    @JsonProperty("last_transaction") LastTransactionData lastTransaction
) {}
