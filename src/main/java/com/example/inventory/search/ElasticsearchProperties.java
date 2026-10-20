package com.example.inventory.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.elasticsearch")
public record ElasticsearchProperties(
        String url,
        String indexName,
        String aliasName,
        boolean initializeIndex
) {
}

