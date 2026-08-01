package com.mercala.imagegen.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for {@link ReplicateImageProvider}.
 *
 * <p>Model inputs live in an open {@link #getInput() map} rather than fixed fields on
 * purpose. Replicate models do not share an input schema — {@code flux-schnell} takes
 * {@code aspect_ratio} and rejects the {@code width}/{@code height} pair that SDXL-style
 * models expect. Binding inputs as a map means switching models is a config change, not
 * a code change.
 */
@Component
@ConfigurationProperties(prefix = "mercala.image-gen.replicate")
public class ReplicateProperties {

    private String baseUrl = "https://api.replicate.com/v1";
    private String apiToken = "";
    private String model = "black-forest-labs/flux-schnell";

    /** How long Replicate should hold the connection open before returning unresolved. */
    private Duration wait = Duration.ofSeconds(60);

    /** Total budget for polling after an unresolved response. */
    private Duration pollTimeout = Duration.ofSeconds(120);

    private Duration pollInterval = Duration.ofMillis(1500);

    /** Extra model inputs sent alongside {@code prompt}. */
    private Map<String, String> input = new LinkedHashMap<>();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
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

    public Duration getWait() {
        return wait;
    }

    public void setWait(Duration wait) {
        this.wait = wait;
    }

    public Duration getPollTimeout() {
        return pollTimeout;
    }

    public void setPollTimeout(Duration pollTimeout) {
        this.pollTimeout = pollTimeout;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Map<String, String> getInput() {
        return input;
    }

    public void setInput(Map<String, String> input) {
        this.input = input;
    }
}
