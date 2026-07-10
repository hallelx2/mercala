package com.mercala.catalog.adapters;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class OpenAiEmbeddingClientUnitTest {

    @Test
    void testLocalOnnxEmbedding() {
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(
                "https://api.openai.com/v1",
                "",  // Empty api key to trigger mock/local mode
                "text-embedding-3-small"
        );

        float[] embedding = client.getEmbedding("comfortable footwear");
        assertThat(embedding).isNotNull().hasSize(1536);

        // Assert magnitude is L2 normalized to approximately 1.0
        double sum = 0;
        for (float f : embedding) {
            sum += f * f;
        }
        assertThat(Math.abs(sum - 1.0)).isLessThan(1e-4);

        // Check that padded indices are valid floats
        assertThat(embedding[0]).isNotNaN();
        assertThat(embedding[383]).isNotNaN();
        // Since we copy 384 values, index 384 and beyond should be 0.0f
        assertThat(embedding[384]).isEqualTo(0.0f);
        assertThat(embedding[1535]).isEqualTo(0.0f);
    }
}
