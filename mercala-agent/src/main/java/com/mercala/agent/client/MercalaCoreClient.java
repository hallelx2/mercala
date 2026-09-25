package com.mercala.agent.client;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.mercala.agent.chat.AgentContext;

@Component
public class MercalaCoreClient {

    private static final Logger log = LoggerFactory.getLogger(MercalaCoreClient.class);
    private static final String TENANT_HEADER = "X-Tenant-ID";

    private final RestTemplate restTemplate;
    private final String coreBaseUrl;
    private final String internalServiceKey;

    public MercalaCoreClient(
            @Value("${mercala.core.base-url:http://localhost:8080}") String coreBaseUrl,
            @Value("${mercala.internal.service-key:mercala-internal-key-secret-2026}") String internalServiceKey) {
        this.restTemplate = new RestTemplate();
        this.coreBaseUrl = coreBaseUrl;
        this.internalServiceKey = internalServiceKey;
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Service-Key", internalServiceKey);
        try {
            AgentContext ctx = AgentContext.current();
            headers.set(TENANT_HEADER, ctx.tenantId().toString());
        } catch (IllegalStateException e) {
            log.warn("AgentContext not available — call will proceed without tenant header");
        }
        String correlationId = org.slf4j.MDC.get("correlation_id");
        if (correlationId != null) {
            headers.set("X-Correlation-Id", correlationId);
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> createProduct(Map<String, Object> body) {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders());
        String url = coreBaseUrl + "/api/products";
        return restTemplate.postForObject(url, entity, Map.class);
    }

    /**
     * The merchant's categories. Used to turn a category the model named — "Apparel" —
     * into the id the catalog API actually takes, which is the only form it accepts.
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> listCategories() {
        String url = coreBaseUrl + "/api/categories";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        java.util.List<Map<String, Object>> body =
                restTemplate.exchange(url, HttpMethod.GET, entity, java.util.List.class).getBody();
        return body == null ? java.util.List.of() : body;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getProduct(UUID productId) {
        String url = coreBaseUrl + "/api/products/" + productId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        return restTemplate.exchange(url, HttpMethod.GET, entity, Map.class).getBody();
    }

    /**
     * The catalogue itself, newest page first. Search needs something to search for;
     * "what am I selling?" is a question with no query in it.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> listProducts(int page, int size) {
        String url = String.format("%s/api/products?page=%d&size=%d", coreBaseUrl, page, size);
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        return restTemplate.exchange(url, HttpMethod.GET, entity, Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> searchCatalog(String query, String mode, int page, int size) {
        String url = String.format("%s/api/search?q=%s&mode=%s&page=%d&size=%d",
                coreBaseUrl,
                java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8),
                mode, page, size);
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        return restTemplate.exchange(url, HttpMethod.GET, entity, Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> updateInventory(UUID variantId, int quantity) {
        Map<String, Object> body = Map.of("quantity", quantity);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders());
        String url = coreBaseUrl + "/api/inventory/" + variantId + "/adjust";
        return restTemplate.postForObject(url, entity, Map.class);
    }

    public void updateProductEmbedding(UUID productId, float[] embedding) {
        String url = coreBaseUrl + "/api/products/" + productId + "/embedding";
        HttpEntity<float[]> entity = new HttpEntity<>(embedding, buildHeaders());
        restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getAuthMe(String authHeader) {
        String url = coreBaseUrl + "/api/auth/me";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", authHeader);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(url, HttpMethod.GET, entity, Map.class).getBody();
    }
}
