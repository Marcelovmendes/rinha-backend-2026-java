package dev.marcelovitor.rinha.http;

public record
ParsedRequest(
    HttpMethod method,
    int pathStart,
    int pathLen,
    int bodyStart,
    int bodyLen,
    int totalLen,
    boolean keepAlive
) {}
