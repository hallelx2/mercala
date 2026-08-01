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
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the provider against a real local HTTP server standing in for the Workers AI
 * REST API, so the assertions cover the request actually built and the response actually
 * parsed rather than a mock's behaviour.
 */
class CloudflareImageProviderTest {

    private static final byte[] IMAGE_BYTES = "fake-image-payload".getBytes(StandardCharsets.UTF_8);
    private static final String ACCOUNT_ID = "acct-123";
    private static final String MODEL = "@cf/black-forest-labs/flux-1-schnell";
    private static final String RUN_PATH = "/client/v4/accounts/" + ACCOUNT_ID + "/ai/run/" + MODEL;

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/client/v4";
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private CloudflareProperties properties() {
        CloudflareProperties props = new CloudflareProperties();
        props.setBaseUrl(baseUrl);
        props.setAccountId(ACCOUNT_ID);
        props.setApiToken("cf-test-token");
        props.setModel(MODEL);
        props.setTimeout(Duration.ofSeconds(10));
        return props;
    }

    private CloudflareImageProvider provider() {
        return new CloudflareImageProvider(new ObjectMapper(), properties());
    }

    @Test
    void decodesBase64ImageFromTheJsonEnvelope() {
        AtomicReference<String> auth = new AtomicReference<>();
        server.createContext(RUN_PATH, exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respondJson(exchange, 200, """
                    {"result":{"image":"%s"},"success":true,"errors":[],"messages":[]}
                    """.formatted(Base64.getEncoder().encodeToString(IMAGE_BYTES)));
        });

        assertThat(provider().generateImage("a red shoe")).isEqualTo(IMAGE_BYTES);
        assertThat(auth.get()).isEqualTo("Bearer cf-test-token");
    }

    @Test
    void passesThroughRawBinaryWhenTheModelReturnsAnImageContentType() {
        // SDXL-family models stream the image directly instead of wrapping it in JSON.
        server.createContext(RUN_PATH, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            respondBytes(exchange, 200, IMAGE_BYTES);
        });

        assertThat(provider().generateImage("a red shoe")).isEqualTo(IMAGE_BYTES);
    }

    @Test
    void sendsPromptAndConfiguredInputsWithCorrectJsonTypes() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext(RUN_PATH, exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondJson(exchange, 200, """
                    {"result":{"image":"%s"},"success":true}
                    """.formatted(Base64.getEncoder().encodeToString(IMAGE_BYTES)));
        });

        CloudflareProperties props = properties();
        props.getInput().put("steps", "4");
        new CloudflareImageProvider(new ObjectMapper(), props).generateImage("a red shoe");

        var json = new ObjectMapper().readTree(body.get());
        assertThat(json.path("prompt").asText()).isEqualTo("a red shoe");
        assertThat(json.path("steps").isNumber())
                .as("steps must be a JSON number; Workers AI rejects a quoted string")
                .isTrue();
        assertThat(json.path("steps").asInt()).isEqualTo(4);
    }

    @Test
    void truncatesPromptsLongerThanTheModelLimit() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext(RUN_PATH, exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondJson(exchange, 200, """
                    {"result":{"image":"%s"},"success":true}
                    """.formatted(Base64.getEncoder().encodeToString(IMAGE_BYTES)));
        });

        CloudflareProperties props = properties();
        props.setMaxPromptLength(50);
        new CloudflareImageProvider(new ObjectMapper(), props).generateImage("x".repeat(500));

        assertThat(new ObjectMapper().readTree(body.get()).path("prompt").asText()).hasSize(50);
    }

    @Test
    void surfacesCloudflareErrorDetailOnAFailureEnvelope() {
        server.createContext(RUN_PATH, exchange -> respondJson(exchange, 200, """
                {"result":null,"success":false,"errors":[{"code":5006,"message":"Account limit reached"}]}
                """));

        assertThatThrownBy(() -> provider().generateImage("a red shoe"))
                .isInstanceOf(ImageGenerationException.class)
                .hasMessageContaining("5006")
                .hasMessageContaining("Account limit reached");
    }

    @Test
    void failsOnAnUnauthorizedResponse() {
        server.createContext(RUN_PATH, exchange -> respondJson(exchange, 401, """
                {"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}
                """));

        assertThatThrownBy(() -> provider().generateImage("a red shoe"))
                .isInstanceOf(ImageGenerationException.class)
                .hasMessageContaining("401");
    }

    @Test
    void failsWhenTheEnvelopeCarriesNoImage() {
        server.createContext(RUN_PATH, exchange ->
                respondJson(exchange, 200, """
                        {"result":{},"success":true}
                        """));

        assertThatThrownBy(() -> provider().generateImage("a red shoe"))
                .isInstanceOf(ImageGenerationException.class)
                .hasMessageContaining("no result.image");
    }

    @Test
    void failsWhenTheImageIsNotValidBase64() {
        server.createContext(RUN_PATH, exchange ->
                respondJson(exchange, 200, """
                        {"result":{"image":"!!!not-base64!!!"},"success":true}
                        """));

        assertThatThrownBy(() -> provider().generateImage("a red shoe"))
                .isInstanceOf(ImageGenerationException.class)
                .hasMessageContaining("not valid base64");
    }

    @Test
    void reportsUnavailableWhenEitherCredentialIsMissing() {
        CloudflareProperties noToken = properties();
        noToken.setApiToken("");
        assertThat(new CloudflareImageProvider(new ObjectMapper(), noToken).isAvailable()).isFalse();

        CloudflareProperties noAccount = properties();
        noAccount.setAccountId("");
        assertThat(new CloudflareImageProvider(new ObjectMapper(), noAccount).isAvailable()).isFalse();

        assertThatThrownBy(() -> new CloudflareImageProvider(new ObjectMapper(), noToken).generateImage("a red shoe"))
                .isInstanceOf(ImageGenerationException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void reportsAvailableWhenBothCredentialsArePresent() {
        assertThat(provider().isAvailable()).isTrue();
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
