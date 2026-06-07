package com.mercala.platform.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the <a href="https://scalar.com">Scalar</a> API reference at {@code /docs},
 * rendering the OpenAPI document that springdoc exposes at {@code /v3/api-docs}.
 */
@RestController
public class ScalarDocsController {

    private static final String SCALAR_HTML = """
            <!doctype html>
            <html>
              <head>
                <title>Mercala API Reference</title>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
              </head>
              <body>
                <script id="api-reference" data-url="/v3/api-docs"></script>
                <script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference"></script>
              </body>
            </html>
            """;

    @GetMapping(value = "/docs", produces = MediaType.TEXT_HTML_VALUE)
    public String scalar() {
        return SCALAR_HTML;
    }
}
