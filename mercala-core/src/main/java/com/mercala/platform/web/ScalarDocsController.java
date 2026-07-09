package com.mercala.platform.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the unified, zero-reload Scalar API documentation portal at {@code /api/v1/docs}.
 * Allows the user to toggle instantly between Core Platform and AI Agent spec sheets.
 */
@RestController
public class ScalarDocsController {

    private static final String PORTAL_HTML = """
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
                    border-radius: 8px;
                    padding: 1.5rem;
                    box-shadow: 0 1px 3px rgba(0,0,0,0.02);
                    transition: border-color 0.3s ease;
                  }

                  .info-card.info-core {
                    border-left: 5px solid var(--accent-green);
                  }

                  .info-card.info-agent {
                    border-left: 5px solid var(--accent-amber);
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

                  .docs-wrapper {
                    width: 100%;
                  }
                  
                  /* Custom overrides to match Scalar UI elements font */
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
                      <button id="btn-core" class="tab-btn active-core" onclick="showService('core')">Core Platform API</button>
                      <button id="btn-agent" class="tab-btn" onclick="showService('agent')">AI Agent API</button>
                    </div>
                  </div>
                </header>

                <div class="info-bar">
                  <div id="info-card" class="info-card info-core">
                    <h1 id="info-title">Mercala Core Platform API</h1>
                    <p id="info-desc">The primary backend of the Mercala platform. Handles catalog management, multitenancy, merchant registration, checkout processes, shopping carts, order pipelines, file uploads, authentication, and secure webhook integrations.</p>
                    <div class="info-meta">
                      <div class="info-meta-item">
                        <span>Scope:</span>
                        <span id="info-scope" class="badge badge-green">Platform/Core</span>
                      </div>
                      <div class="info-meta-item">
                        <span>Endpoint Base:</span>
                        <code id="info-base">https://mercalaapi.hallelx2.com/api/v1</code>
                      </div>
                      <div class="info-meta-item">
                        <span>Spec Path:</span>
                        <code id="info-spec">/v3/api-docs</code>
                      </div>
                    </div>
                  </div>
                </div>

                <div class="api-container">
                  <!-- Core Docs Reference View -->
                  <div id="docs-core" class="docs-wrapper"></div>
                  
                  <!-- Agent Docs Reference View -->
                  <div id="docs-agent" class="docs-wrapper" style="display: none;"></div>
                </div>

                <!-- Load Scalar reference library -->
                <script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference"></script>
                
                <script>
                  // Programmatically initialize both Scalar specs on page load
                  Scalar.createApiReference('#docs-core', {
                    url: '/v3/api-docs'
                  });

                  Scalar.createApiReference('#docs-agent', {
                    url: '/api/v1/agent/v3/api-docs'
                  });

                  // Client-side instant tab toggle (zero-reload)
                  function showService(service) {
                    const btnCore = document.getElementById('btn-core');
                    const btnAgent = document.getElementById('btn-agent');
                    const docsCore = document.getElementById('docs-core');
                    const docsAgent = document.getElementById('docs-agent');
                    
                    const infoCard = document.getElementById('info-card');
                    const infoTitle = document.getElementById('info-title');
                    const infoDesc = document.getElementById('info-desc');
                    const infoScope = document.getElementById('info-scope');
                    const infoBase = document.getElementById('info-base');
                    const infoSpec = document.getElementById('info-spec');

                    if (service === 'core') {
                      docsCore.style.display = 'block';
                      docsAgent.style.display = 'none';
                      
                      btnCore.className = 'tab-btn active-core';
                      btnAgent.className = 'tab-btn';
                      
                      infoCard.className = 'info-card info-core';
                      infoTitle.innerText = 'Mercala Core Platform API';
                      infoDesc.innerText = 'The primary backend of the Mercala platform. Handles catalog management, multitenancy, merchant registration, checkout processes, shopping carts, order pipelines, file uploads, authentication, and secure webhook integrations.';
                      infoScope.innerText = 'Platform/Core';
                      infoScope.className = 'badge badge-green';
                      infoBase.innerText = 'https://mercalaapi.hallelx2.com/api/v1';
                      infoSpec.innerText = '/v3/api-docs';
                    } else {
                      docsCore.style.display = 'none';
                      docsAgent.style.display = 'block';
                      
                      btnCore.className = 'tab-btn';
                      btnAgent.className = 'tab-btn active-agent';
                      
                      infoCard.className = 'info-card info-agent';
                      infoTitle.innerText = 'Mercala AI Agent Service API';
                      infoDesc.innerText = 'Powered by Spring AI, this microservice handles all natural language conversational flows. It enables merchant agents to dynamically query/create products and variants, and shopper agents to search, ground, and recommend catalog items contextually.';
                      infoScope.innerText = 'Agent/AI';
                      infoScope.className = 'badge badge-amber';
                      infoBase.innerText = 'https://mercalaapi.hallelx2.com/api/v1/agent';
                      infoSpec.innerText = '/api/v1/agent/v3/api-docs';
                    }
                  }
                </script>
              </body>
            </html>
            """;

    @GetMapping(value = "/api/v1/docs", produces = MediaType.TEXT_HTML_VALUE)
    public String scalar() {
        return PORTAL_HTML;
    }
}
