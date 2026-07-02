This PR implements Agent security guardrails including rate limiting and prompt-injection mitigations (HAL-155).

### Key Features:
- **AgentGuardrailService**:
  - Implements a thread-safe token bucket algorithm for rate limiting on a per-user basis. Refill rate and bucket capacity are configurable via `application.yml`.
  - Scans prompt content against regex patterns designed to catch common prompt injection vectors (e.g. system override requests, god mode declarations, system prompt revealing, and tenant scoping bypass).
- **Global Controller Advice**:
  - Added `AgentSecurityExceptionHandler` to intercept `SecurityException` and `RateLimitExceededException` mapping them to appropriate HTTP statuses: `400 Bad Request` and `429 Too Many Requests`.
- **Controllers Integration**:
  - Wired security guardrail checks into both `MerchantChatController` and `ShopperChatController` endpoints before messages reach the Spring AI agents.

### Tests (54 passing):
- `AgentGuardrailServiceTest`: Verifies prompt scanning for injection matches and token bucket consumption lifecycle (isolated per user).
- `AgentGuardrailsIntegrationTest`: Integration tests ensuring HTTP mappings and error responses are properly handled when security policies are breached.

Closes HAL-155
