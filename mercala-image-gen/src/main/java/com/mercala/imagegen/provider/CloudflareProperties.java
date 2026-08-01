package com.mercala.imagegen.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for {@link CloudflareImageProvider}.
 *
 * <p>Workers AI runs on a recurring free allowance rather than prepaid credit, which is
 * why this is the default provider: it costs nothing per day and does not silently stop
 * working when a balance runs out.
 *
 * <p>As with Replicate, extra model inputs are an open map — Workers AI hosts several
 * image models and they do not share an input schema. {@code flux-1-schnell} takes
 * {@code steps} (max 8); the SDXL models take {@code num_steps}, {@code width},
 * {@code height} and {@code negative_prompt} instead.
 */
@Component
@ConfigurationProperties(prefix = "mercala.image-gen.cloudflare")
public class CloudflareProperties {

    private String baseUrl = "https://api.cloudflare.com/client/v4";
    private String accountId = "";
    private String apiToken = "";
    private String model = "@cf/black-forest-labs/flux-1-schnell";
    private Duration timeout = Duration.ofSeconds(60);

    /**
     * Workers AI rejects prompts longer than this. Truncating is better than letting a
     * long generated prompt fail the request outright.
     */
    private int maxPromptLength = 2048;

    /** Extra model inputs sent alongside {@code prompt}. */
    private Map<String, String> input = new LinkedHashMap<>();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxPromptLength() {
        return maxPromptLength;
    }

    public void setMaxPromptLength(int maxPromptLength) {
        this.maxPromptLength = maxPromptLength;
    }

    public Map<String, String> getInput() {
        return input;
    }

    public void setInput(Map<String, String> input) {
        this.input = input;
    }
}
