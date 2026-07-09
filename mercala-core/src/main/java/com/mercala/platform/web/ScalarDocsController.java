package com.mercala.platform.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the unified Scalar API documentation portal at {@code /api/v1/docs}.
 * Allows the user to toggle between Core Platform APIs and AI Agent APIs.
 */
@RestController
public class ScalarDocsController {

    private static final String PORTAL_TEMPLATE = """
            <!doctype html>
            <html lang="en">
              <head>
                <title>Mercala API Portal</title>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@500;700;800&family=Geist+Sans:wght@400;500;600&family=Geist+Mono&display=swap" rel="stylesheet">
                <style>
                  :root {
                    --bg-cream: #fbfaf7;
                    --text-ink: #1c1a17;
                    --border-neutral: #e6e4df;
                    --accent-green: #2b5c3e;
                    --accent-amber: #b35900;
                  }
                  
                  body {
                    margin: 0;
                    padding: 0;
                    background-color: var(--bg-cream);
                    color: var(--text-ink);
                    font-family: 'Geist Sans', -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  }

                  header {
                    border-bottom: 2px solid var(--text-ink);
                    background-color: var(--bg-cream);
                    position: sticky;
                    top: 0;
                    z-index: 1000;
                  }

                  .nav-container {
                    max-width: 1400px;
                    margin: 0 auto;
                    padding: 1.25rem 2rem;
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    flex-wrap: wrap;
                    gap: 1rem;
                  }

                  .branding {
                    font-family: 'Plus Jakarta Sans', sans-serif;
                    font-weight: 800;
                    font-size: 1.5rem;
                    letter-spacing: -0.03em;
                    color: var(--text-ink);
                    display: flex;
                    align-items: center;
                    gap: 0.5rem;
                  }

                  .service-tabs {
                    display: flex;
                    gap: 0.5rem;
                    background: #f1efea;
                    padding: 0.25rem;
                    border-radius: 8px;
                    border: 1px solid var(--border-neutral);
                  }

                  .tab-btn {
                    font-family: 'Geist Sans', sans-serif;
                    font-weight: 600;
                    font-size: 0.9rem;
                    padding: 0.5rem 1.25rem;
                    border-radius: 6px;
                    border: none;
                    cursor: pointer;
                    text-decoration: none;
                    transition: all 0.2s ease;
                    color: #615f5a;
                    background: transparent;
                  }

                  .tab-btn:hover {
                    color: var(--text-ink);
                    background: #e6e4df;
                  }

                  .tab-btn.active-core {
                    background-color: var(--accent-green);
                    color: #ffffff;
                  }

                  .tab-btn.active-agent {
                    background-color: var(--accent-amber);
                    color: #ffffff;
                  }

                  .info-bar {
                    max-width: 1400px;
                    margin: 0 auto;
                    padding: 1.5rem 2rem;
                  }

                  .info-card {
                    background: #ffffff;
                    border: 1px solid var(--border-neutral);
                    border-left: 5px solid %s;
                    border-radius: 8px;
                    padding: 1.5rem;
                    box-shadow: 0 1px 3px rgba(0,0,0,0.02);
                  }

                  .info-card h1 {
                    font-family: 'Plus Jakarta Sans', sans-serif;
                    font-size: 1.3rem;
                    font-weight: 700;
                    margin-top: 0;
                    margin-bottom: 0.5rem;
                    color: var(--text-ink);
                  }

                  .info-card p {
                    font-size: 0.95rem;
                    line-height: 1.5;
                    margin: 0;
                    color: #4a4844;
                  }

                  .info-meta {
                    display: flex;
                    gap: 1.5rem;
                    margin-top: 1rem;
                    font-family: 'Geist Mono', monospace;
                    font-size: 0.8rem;
                    flex-wrap: wrap;
                  }

                  .info-meta-item {
                    display: flex;
                    align-items: center;
                    gap: 0.4rem;
                  }

                  .badge {
                    padding: 0.15rem 0.4rem;
                    border-radius: 4px;
                    font-weight: bold;
                    color: #ffffff;
                    text-transform: uppercase;
                  }

                  .badge-green { background-color: var(--accent-green); }
                  .badge-amber { background-color: var(--accent-amber); }

                  .api-container {
                    border-top: 1px solid var(--border-neutral);
                  }
                  
                  /* Override default Scalar font family and some styles dynamically */
                  .scalar-app {
                    --scalar-font: 'Geist Sans', sans-serif !important;
                    --scalar-font-code: 'Geist Mono', monospace !important;
                  }
                </style>
              </head>
              <body>
                <header>
                  <div class="nav-container">
                    <div class="branding">
                      <span>MERCALA</span>
                      <span style="font-weight: 500; font-size: 1.1rem; color: #615f5a; border-left: 1px solid #dcdad5; padding-left: 0.5rem;">API GATEWAY</span>
                    </div>
                    <div class="service-tabs">
                      <a href="/api/v1/docs?service=core" class="tab-btn %s">Core Platform API</a>
                      <a href="/api/v1/docs?service=agent" class="tab-btn %s">AI Agent API</a>
                    </div>
                  </div>
                </header>

                <div class="info-bar">
                  <div class="info-card">
                    <h1>%s</h1>
                    <p>%s</p>
                    <div class="info-meta">
                      <div class="info-meta-item">
                        <span>Scope:</span>
                        <span class="badge %s">%s</span>
                      </div>
                      <div class="info-meta-item">
                        <span>Endpoint Base:</span>
                        <code>%s</code>
                      </div>
                      <div class="info-meta-item">
                        <span>Spec Path:</span>
                        <code>%s</code>
                      </div>
                    </div>
                  </div>
                </div>

                <div class="api-container">
                  <script id="api-reference" data-url="%s"></script>
                  <script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference"></script>
                </div>
              </body>
            </html>
            """;

    @GetMapping(value = "/api/v1/docs", produces = MediaType.TEXT_HTML_VALUE)
    public String scalar(@RequestParam(value = "service", defaultValue = "core") String service) {
        if ("agent".equalsIgnoreCase(service)) {
            return String.format(PORTAL_TEMPLATE,
                    "var(--accent-amber)",                     // Left border color
                    "",                                        // Core tab active class
                    "active-agent",                            // Agent tab active class
                    "Mercala AI Agent Service API",            // Card title
                    "Powered by Spring AI, this microservice handles all natural language conversational flows. " +
                    "It enables merchant agents to dynamically query/create products and variants, and shopper agents to search, " +
                    "ground, and recommend catalog items contextually.",
                    "badge-amber",                             // Badge color class
                    "Agent/AI",                                // Scope text
                    "https://mercalaapi.hallelx2.com/api/v1/agent", // Base URL
                    "/api/v1/agent/v3/api-docs",               // Spec Path text
                    "/api/v1/agent/v3/api-docs"                // Scalar actual load path
            );
        } else {
            return String.format(PORTAL_TEMPLATE,
                    "var(--accent-green)",                     // Left border color
                    "active-core",                             // Core tab active class
                    "",                                        // Agent tab active class
                    "Mercala Core Platform API",               // Card title
                    "The primary backend of the Mercala platform. Handles catalog management, multitenancy, " +
                    "merchant registration, checkout processes, shopping carts, order pipelines, file uploads, " +
                    "authentication, and secure webhook integrations.",
                    "badge-green",                             // Badge color class
                    "Platform/Core",                           // Scope text
                    "https://mercalaapi.hallelx2.com/api/v1",  // Base URL
                    "/v3/api-docs",                            // Spec Path text
                    "/v3/api-docs"                             // Scalar actual load path
            );
        }
    }
}
