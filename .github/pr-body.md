This PR implements the Shopper Discovery Agent over hybrid search (HAL-153).

### Key Additions:
- **ShopperAgentService**: Conversational agent service geared towards shoppers. It is read-only, using only `searchCatalog` and `getProduct` tools. The system prompt enforces grounded, citation-backed recommendations to prevent hallucinations.
- **ShopperChatController**: Exposes `POST /api/agent/shopper/chat` with request/response body validation.
- **Tests**:
  - `ShopperAgentServiceTest`: 8 tests verifying role constraints (SHOPPER), tenant isolation, message context propagation, and read-only tool limits.
  - `ShopperChatControllerTest`: 3 tests verifying grounded response serialization and path isolation from the merchant agent endpoint.

Closes HAL-153
