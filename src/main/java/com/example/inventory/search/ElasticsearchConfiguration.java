package com.example.inventory.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(ElasticsearchProperties.class)
public class ElasticsearchConfiguration {

    @Bean(destroyMethod = "close")
    RestClient elasticsearchRestClient(ElasticsearchProperties properties) {
        URI uri = URI.create(properties.url());
        int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
        return RestClient.builder(new HttpHost(uri.getHost(), port, uri.getScheme())).build();
    }

    @Bean(destroyMethod = "close")
    ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper());
    }

    @Bean
    ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}

