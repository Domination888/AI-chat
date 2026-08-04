package org.example.aichat.search;

public interface WebSearchGateway {
    SearchResponse search(SearchRequest request);
    SearchHealth health();
}
