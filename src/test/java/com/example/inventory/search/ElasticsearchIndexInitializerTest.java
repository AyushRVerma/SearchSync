package com.example.inventory.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.PutAliasResponse;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ElasticsearchIndexInitializerTest {

    @Mock
    private ElasticsearchClient client;

    @Mock
    private ElasticsearchIndicesClient indicesClient;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(client.indices()).thenReturn(indicesClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsIndexAndAliasWhenNeitherExists() throws IOException {
        when(indicesClient.exists(any(Function.class))).thenReturn(new BooleanResponse(false));
        when(indicesClient.create(any(Function.class))).thenReturn(new CreateIndexResponse.Builder().acknowledged(true).index("products-v1").shardsAcknowledged(true).build());
        when(indicesClient.existsAlias(any(Function.class))).thenReturn(new BooleanResponse(false));
        when(indicesClient.putAlias(any(Function.class))).thenReturn(new PutAliasResponse.Builder().acknowledged(true).build());

        ElasticsearchProperties properties = new ElasticsearchProperties("http://localhost:9200", "products-v1", "products", true);
        ElasticsearchIndexInitializer initializer = new ElasticsearchIndexInitializer(client, properties);

        initializer.start();

        verify(indicesClient).create(any(Function.class));
        verify(indicesClient).putAlias(any(Function.class));
        assertThat(initializer.isRunning()).isTrue();
        assertThat(initializer.getPhase()).isEqualTo(-1000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsAliasWhenIndexExistsButAliasDoesNotExist() throws IOException {
        when(indicesClient.exists(any(Function.class))).thenReturn(new BooleanResponse(true));
        when(indicesClient.existsAlias(any(Function.class))).thenReturn(new BooleanResponse(false));
        when(indicesClient.putAlias(any(Function.class))).thenReturn(new PutAliasResponse.Builder().acknowledged(true).build());

        ElasticsearchProperties properties = new ElasticsearchProperties("http://localhost:9200", "products-v1", "products", true);
        ElasticsearchIndexInitializer initializer = new ElasticsearchIndexInitializer(client, properties);

        initializer.start();

        verify(indicesClient, never()).create(any(Function.class));
        verify(indicesClient).putAlias(any(Function.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNothingWhenBothIndexAndAliasExist() throws IOException {
        when(indicesClient.exists(any(Function.class))).thenReturn(new BooleanResponse(true));
        when(indicesClient.existsAlias(any(Function.class))).thenReturn(new BooleanResponse(true));

        ElasticsearchProperties properties = new ElasticsearchProperties("http://localhost:9200", "products-v1", "products", true);
        ElasticsearchIndexInitializer initializer = new ElasticsearchIndexInitializer(client, properties);

        initializer.start();

        verify(indicesClient, never()).create(any(Function.class));
        verify(indicesClient, never()).putAlias(any(Function.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsInitializationWhenDisabled() throws IOException {
        ElasticsearchProperties properties = new ElasticsearchProperties("http://localhost:9200", "products-v1", "products", false);
        ElasticsearchIndexInitializer initializer = new ElasticsearchIndexInitializer(client, properties);

        initializer.start();

        verify(indicesClient, never()).exists(any(Function.class));
        verify(indicesClient, never()).create(any(Function.class));
        verify(indicesClient, never()).putAlias(any(Function.class));
        assertThat(initializer.isRunning()).isTrue();
    }
}
