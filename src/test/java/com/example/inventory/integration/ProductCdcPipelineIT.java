package com.example.inventory.integration;

import com.example.inventory.InventoryApplication;
import com.example.inventory.product.api.CreateProductRequest;
import com.example.inventory.product.api.PatchProductRequest;
import com.example.inventory.product.api.ProductResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.containers.wait.strategy.Wait;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = InventoryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductCdcPipelineIT {

    private static final Network NETWORK = Network.newNetwork();

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine")
            .withDatabaseName("inventory_db")
            .withUsername("postgres")
            .withPassword("postgres")
            .withCommand("postgres", "-c", "wal_level=logical", "-c", "max_wal_senders=10",
                    "-c", "max_replication_slots=10", "-c", "max_slot_wal_keep_size=1024MB")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("integration/postgres-init.sql"),
                    "/docker-entrypoint-initdb.d/001-test-users.sql")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres");

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1")
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka")
            .withListener("kafka:19092");

    private static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.5.2"))
            .withEnv("xpack.security.enabled", "false")
            .withNetwork(NETWORK)
            .withNetworkAliases("elasticsearch");

    private static final GenericContainer<?> CONNECT = new GenericContainer<>(
            DockerImageName.parse("quay.io/debezium/connect:3.4.3.Final"))
            .withNetwork(NETWORK)
            .withExposedPorts(8083)
            .withEnv("BOOTSTRAP_SERVERS", "kafka:19092")
            .withEnv("GROUP_ID", "product-connect-integration")
            .withEnv("CONFIG_STORAGE_TOPIC", "it-connect-configs")
            .withEnv("OFFSET_STORAGE_TOPIC", "it-connect-offsets")
            .withEnv("STATUS_STORAGE_TOPIC", "it-connect-statuses")
            .withEnv("CONFIG_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("OFFSET_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("STATUS_STORAGE_REPLICATION_FACTOR", "1")
            .dependsOn(POSTGRES, KAFKA)
            .waitingFor(Wait.forHttp("/connectors").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static ProductResponse createdProduct;

    static {
        POSTGRES.start();
        KAFKA.start();
        ELASTICSEARCH.start();
        CONNECT.start();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @BeforeAll
    static void registerDebeziumConnector() throws Exception {
        registerConnector();
    }

    @AfterAll
    static void stopInfrastructure() {
        CONNECT.stop();
        ELASTICSEARCH.stop();
        KAFKA.stop();
        POSTGRES.stop();
        NETWORK.close();
    }

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("app.elasticsearch.url", () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
        registry.add("app.elasticsearch.initialize-index", () -> true);
    }

    @Test
    @Order(1)
    void createFlowsFromPostgresThroughDebeziumToSearch() {
        CreateProductRequest request = new CreateProductRequest(
                "PHONE-15-IT", "iPhone 15 Integration", "CDC integration product", "Electronics",
                new BigDecimal("799.00"), 12, true);

        ResponseEntity<ProductResponse> response = rest.postForEntity(api("/api/v1/products"), request,
                ProductResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        createdProduct = response.getBody();
        assertThat(createdProduct).isNotNull();

        Awaitility.await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    JsonNode search = search("iphne");
                    assertThat(search.path("total").asLong()).isEqualTo(1L);
                    assertThat(search.path("items").get(0).path("id").asText())
                            .isEqualTo(createdProduct.id().toString());
                });
    }

    @Test
    @Order(2)
    void updateIsProjectedToSearch() {
        PatchProductRequest patch = new PatchProductRequest(
                null, null, null, new BigDecimal("749.00"), null, null, createdProduct.version());

        ResponseEntity<ProductResponse> response = rest.exchange(
                api("/api/v1/products/" + createdProduct.id()), HttpMethod.PATCH,
                new HttpEntity<>(patch), ProductResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        createdProduct = response.getBody();

        Awaitility.await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(search("iPhone 15 Integration").path("items").get(0)
                        .path("price").decimalValue()).isEqualByComparingTo("749.00"));
    }

    @Test
    @Order(3)
    void deleteRemovesDocumentFromSearch() {
        rest.delete(api("/api/v1/products/" + createdProduct.id()));

        Awaitility.await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(search("iPhone 15 Integration").path("total").asLong()).isZero());
    }

    private JsonNode search(String query) throws Exception {
        String response = rest.getForObject(api("/api/v1/search?q={query}"), String.class, query);
        return JSON.readTree(response);
    }

    private String api(String path) {
        return "http://localhost:" + port + path;
    }

    private static void registerConnector() throws Exception {
        ObjectNode config = JSON.createObjectNode();
        config.put("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        config.put("plugin.name", "pgoutput");
        config.put("database.hostname", "postgres");
        config.put("database.port", "5432");
        config.put("database.user", "debezium");
        config.put("database.password", "debezium");
        config.put("database.dbname", "inventory_db");
        config.put("topic.prefix", "inventory");
        config.put("table.include.list", "public.products");
        config.put("slot.name", "product_cdc_it_slot");
        config.put("publication.name", "product_cdc_it_publication");
        config.put("publication.autocreate.mode", "filtered");
        config.put("snapshot.mode", "initial");
        config.put("decimal.handling.mode", "string");
        config.put("key.converter", "org.apache.kafka.connect.json.JsonConverter");
        config.put("key.converter.schemas.enable", "false");
        config.put("value.converter", "org.apache.kafka.connect.json.JsonConverter");
        config.put("value.converter.schemas.enable", "false");

        ObjectNode requestBody = JSON.createObjectNode();
        requestBody.put("name", "product-cdc-integration");
        requestBody.set("config", config);

        URI endpoint = URI.create("http://" + CONNECT.getHost() + ":" + CONNECT.getMappedPort(8083)
                + "/connectors");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isIn(200, 201);

        URI statusEndpoint = URI.create(endpoint + "/product-cdc-integration/status");
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    HttpResponse<String> status = HTTP.send(
                            HttpRequest.newBuilder(statusEndpoint).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertThat(status.statusCode()).isEqualTo(200);
                    JsonNode statusJson = JSON.readTree(status.body());
                    assertThat(statusJson.path("connector").path("state").asText()).isEqualTo("RUNNING");
                    assertThat(statusJson.path("tasks").get(0).path("state").asText()).isEqualTo("RUNNING");
                });
    }
}

