This PR implements Kafka event wiring for product re-embedding and AI image requests (HAL-154).

### Key Features:
- **Shared Contracts (mercala-contracts)**:
  - `ProductEvent`: Carries `productId`, `tenantId`, and `eventType` ("ADDED", "UPDATED", "DELETED") on the `product.events` topic.
  - `ImageRequestEvent`: Carries `productId`, `tenantId`, and a description `prompt` on the `image.requests` topic.
- **Producer (mercala-core)**:
  - Updated `ProductEventListener` to publish to the `product.events` Kafka topic inside transaction post-commit phase (conditional on `mercala.kafka.enabled` configuration).
  - Added `PUT /api/products/{id}/embedding` secure REST endpoint in `ProductController` to allow downstream agents/services to save generated embeddings.
- **Consumer & Client (mercala-agent)**:
  - `ProductEventKafkaConsumer`: Consumes `product.events`, calls Spring AI's `EmbeddingModel` to generate embeddings, and updates the core database via the new PUT API.
  - `MercalaCoreClient`: Refactored and simplified REST integration with `mercala-core` (catalog, inventory, search, and embedding).
  - `ImageRequestProducer`: Publishes image generation requests to `image.requests`.
  - `ImageTools`: Exposes a new `requestProductImage` chat tool that merchants can invoke to request AI image generation.

### Tests (43 passing):
- `ProductEventKafkaConsumerTest`: Verifies consumer flow with an `@EmbeddedKafka` broker, Mockito mock beans for Spring AI embedding, and `MercalaCoreClient`.
- `ImageRequestProducerTest`: Verifies producer serialization and routing using a test consumer.
- `ImageToolsTest`: Unit tests for the new `requestProductImage` agent tool.

Closes HAL-154
