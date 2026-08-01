package com.mercala.imagegen.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the provider against a real local HTTP server standing in for Replicate.
 * Mocking the JDK HttpClient would test the mock; this tests the actual request the
 * provider builds and the actual JSON it parses back.
 */
class ReplicateImageProviderTest {

    private static final byte[] PNG_BYTES = "fake-png-payload".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private String baseUrl;
    private final List<String> authHeaders = new ArrayList<>();
    private final List<String> requestedPaths = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

        // The generated image itself, referenced by the prediction output.
        server.createContext("/output.png", exchange -> respondBytes(exchange, 200, PNG_BYTES));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ReplicateImageProvider provider(String token) {
        return new ReplicateImageProvider(new ObjectMapper(), properties(token));
    }

    private ReplicateProperties properties(String token) {
        ReplicateProperties props = new ReplicateProperties();
        props.setBaseUrl(baseUrl);
        props.setApiToken(token);
        props.setModel("black-forest-labs/flux-schnell");
        props.setWait(java.time.Duration.ofSeconds(5));
        props.setPollTimeout(java.time.Duration.ofSeconds(10));
        props.setPollInterval(java.time.Duration.ofMillis(50));
        return props;
    }

    @Test
    void returnsImageBytesWhenThePredictionResolvesImmediately() {
        server.createContext("/v1/models/black-forest-labs/flux-schnell/predictions", exchange -> {
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            respondJson(exchange, 201, """
                    {"id":"pred-1","status":"succeeded","output":["%s/output.png"]}
                    """.formatted(rootUrl()));
        });

        byte[] result = provider("r8-test-token").generateImage("a red shoe");

        assertThat(result).isEqualTo(PNG_BYTES);
        assertThat(authHeaders).containsExactly("Bearer r8-test-token");
    }

    @Test
    void acceptsABareStringOutputAsWellAsAnArray() {
        server.createContext("/v1/models/black-forest-labs/flux-schnell/predictions", exchange ->
                respondJson(exchange, 201, """
                        {"id":"pred-1","status":"succeeded","output":"%s/output.png"}
                        """.formatted(rootUrl())));

        assertThat(provider("r8-test-token").generateImage("a red shoe")).isEqualTo(PNG_BYTES);
    }

    @Test
    void pollsUntilThePredictionFinishesWhenItIsStillRunning() {
        AtomicInteger polls = new AtomicInteger();

        server.createContext("/v1/models/black-forest-labs/flux-schnell/predictions", exchange ->
                respondJson(exchange, 201, """
                        {"id":"pred-1","status":"processing"}
                        """));

        server.createContext("/v1/predictions/pred-1", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            // Stay in "processing" for the first two polls, then resolve.
            if (polls.incrementAndGet() < 3) {
                respondJson(exchange, 200, """
                        {"id":"pred-1","status":"processing"}
                        """);
            } else {
                respondJson(exchange, 200, """
                        {"id":"pred-1","status":"succeeded","output":["%s/output.png"]}
                        """.formatted(rootUrl()));
            }
        });

        byte[] result = provider("r8-test-token").generateImage("a red shoe");

        assertThat(result).isEqualTo(PNG_BYTES);
        assertThat(polls.get()).isEqualTo(3);
    }

    @Test
    void failsWithTheReportedErrorWhenThePredictionFails() {
        server.createContext("/v1/models/black-forest-labs/flux-schnell/predictions", exchange ->
                respondJson(exchange, 201, """
                        {"id":"pred-1","status":"failed","error":"NSFW content detected"}
                        """));

        assertThatThrownBy(() -> provider("r8-test-token").generateImage("a red shoe"))
                .isInstanceOf(ImageGenerationException.class)
                .hasMessageContaining("failed")
                .hasMessageContaining("NSFW content detected");
    }

    @Test
    void failsOnAnUnauthorizedResponse() {
        server.createContext("/v1/models/black-forest-labs/flux-schnell/predictions", exchange ->
                respondJson(exchange, 401, """
                        {"detail":"Invalid token"}
                        """));

        assertThatThrownBy(() -> provider("bad-token").generateImage("a red shoe"))
                .isInstanceOf(ImageGenerationException.class)
                .hasMessageContaining("401");
    }

    @Test
    void failsWhenASucceededPredictionCarriesNoOutput() {
        server.createContext("/v1/models/black-forest-labs/flux-schnell/predictions", exchange ->
                respondJson(exchange, 201, """
                        {"id":"pred-1","status":"succeeded","output":[]}
                        """));

        assertThatThrownBy(() -> provider("r8-test-token").generateImage("a red shoe"))
                .isInstanceOf(ImageGenerationException.class)
                .hasMessageContaining("no usable output URL");
    }

    @Test
    void reportsUnavailableWithoutATokenSoTheRouterSkipsIt() {
        ReplicateImageProvider provider = provider("");

        assertThat(provider.isAvailable()).isFalse();
        assertThatThrownBy(() -> provider.generateImage("a red shoe"))
                .isInstanceOf(ImageGenerationException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void reportsAvailableWhenATokenIsPresent() {
        assertThat(provider("r8-test-token").isAvailable()).isTrue();
    }

    @Test
    void sendsConfiguredModelInputsWithCorrectJsonTypes() throws Exception {
        var capturedBody = new java.util.concurrent.atomic.AtomicReference<String>();

        server.createContext("/v1/models/black-forest-labs/flux-schnell/predictions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondJson(exchange, 201, """
                    {"id":"pred-1","status":"succeeded","output":["%s/output.png"]}
                    """.formatted(rootUrl()));
        });

        ReplicateProperties props = properties("r8-test-token");
        props.getInput().put("aspect_ratio", "1:1");
        props.getInput().put("output_format", "png");
        props.getInput().put("num_outputs", "1");
        props.getInput().put("disable_safety_checker", "false");

        new ReplicateImageProvider(new ObjectMapper(), props).generateImage("a red shoe");

        var input = new ObjectMapper().readTree(capturedBody.get()).path("input");
        assertThat(input.path("prompt").asText()).isEqualTo("a red shoe");
        assertThat(input.path("aspect_ratio").asText()).isEqualTo("1:1");
        assertThat(input.path("output_format").asText()).isEqualTo("png");
        assertThat(input.path("num_outputs").isNumber())
                .as("numeric inputs must be JSON numbers, not quoted strings")
                .isTrue();
        assertThat(input.path("disable_safety_checker").isBoolean())
                .as("boolean inputs must be JSON booleans, not quoted strings")
                .isTrue();
        assertThat(input.has("width"))
                .as("flux-schnell has no width parameter; nothing should invent one")
                .isFalse();
    }

    private String rootUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        respondBytes(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respondBytes(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
