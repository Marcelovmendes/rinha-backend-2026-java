package dev.marcelovitor.rinha.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TerminalData(
    @JsonProperty("is_online")    boolean isOnline,
    @JsonProperty("card_present") boolean cardPresent,
    @JsonProperty("km_from_home") double  kmFromHome
) {}
