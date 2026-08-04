package org.example.aichat.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchSource {
    private String title;
    private String url;
    private String publishedAt;
    private String engine;
    private String snippet;
    @Builder.Default
    private List<String> excerpts = new ArrayList<>();
    private double score;
    private boolean pageRead;
}
