package com.mercala.catalog.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a person types is text, not query syntax.
 *
 * <p>pg_search reads its argument as a query language, so ordinary punctuation was a parse
 * error rather than a search: "t-shirt", or any product whose own name carries a dash,
 * came back as a 500 from /api/search — <em>could not parse query string</em>. That also
 * broke the merchant agent, which resolves a product by name through the same path.
 */
class LexicalQueryTest {

    @Test
    void aHyphenatedWordIsSearchable() {
        assertThat(ProductService.toLexicalQuery("t-shirt")).isEqualTo("\"t-shirt\"");
    }

    @Test
    void everyTermIsQuotedSeparately() {
        assertThat(ProductService.toLexicalQuery("River Blue Serving Bowl - Wide"))
                .isEqualTo("\"River\" \"Blue\" \"Serving\" \"Bowl\" \"-\" \"Wide\"");
    }

    @Test
    void quotesAndBackslashesCannotEscapeTheTerm() {
        assertThat(ProductService.toLexicalQuery("say \"hello\"")).isEqualTo("\"say\" \"hello\"");
        assertThat(ProductService.toLexicalQuery("back\\slash")).isEqualTo("\"backslash\"");
    }

    @Test
    void anEmptyQueryStaysEmpty() {
        assertThat(ProductService.toLexicalQuery("   ")).isEmpty();
        assertThat(ProductService.toLexicalQuery(null)).isEmpty();
    }
}
