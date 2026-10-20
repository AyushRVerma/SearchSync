package com.example.inventory.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ElasticsearchIndexInitializer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchIndexInitializer.class);

    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ElasticsearchIndexInitializer(ElasticsearchClient client, ElasticsearchProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void start() {
        if (!properties.initializeIndex()) {
            running.set(true);
            return;
        }
        initialize();
        running.set(true);
    }

    public void initialize() {
        try {
            boolean indexExists = client.indices().exists(request -> request.index(properties.indexName())).value();
            if (!indexExists) {
                try (InputStream mapping = new ClassPathResource("elasticsearch/products-index.json").getInputStream()) {
                    client.indices().create(builder -> builder.index(properties.indexName()).withJson(mapping));
                }
                log.info("Created Elasticsearch index {}", properties.indexName());
            } else {
                log.info("Elasticsearch index {} already exists", properties.indexName());
            }

            boolean aliasExists = client.indices().existsAlias(request -> request
                    .index(properties.indexName())
                    .name(properties.aliasName())).value();
            if (!aliasExists) {
                client.indices().putAlias(builder -> builder
                        .index(properties.indexName())
                        .name(properties.aliasName()));
                log.info("Created Elasticsearch alias {} pointing to {}", properties.aliasName(), properties.indexName());
            } else {
                log.info("Elasticsearch alias {} already points to {}", properties.aliasName(), properties.indexName());
            }
        } catch (Exception exception) {
            log.error("Failed to initialize Elasticsearch index or alias", exception);
            throw new IllegalStateException("Failed to initialize Elasticsearch index or alias", exception);
        }
    }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // Runs before KafkaListenerEndpointRegistry (which defaults to Integer.MAX_VALUE - 2048)
        return -1000;
    }
}

