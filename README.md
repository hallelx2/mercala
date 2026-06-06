# Mercala

> **Agent-native, multi-tenant e-commerce platform.** Shopify-class, but the primary interface is *agentic*: merchants run their store by talking to agents (add products by chat, AI product imagery), and shoppers discover and buy by talking to agents.

Built with **Java 21 · Spring Boot · Postgres · Kafka · Spring AI**. Planning + roadmap live in **Linear** (initiative *Mercala*, team Hallelx2).

---

## Architecture (at a glance)

- **Topology:** monorepo + a **modular-monolith core** (`mercala-core`) + two carved-out services added later (`mercala-agent`, `mercala-image-gen`), designed for extraction.
- **Multi-tenancy:** shared DB + `tenant_id`, defense-in-depth = RBAC (code) + Hibernate tenant filter + **Postgres Row-Level Security**.
- **Search:** Postgres-native hybrid — `pg_search` (BM25) + `pgvector` (semantic) + RRF fusion. No Elasticsearch.
- **Payments:** `PaymentProvider` strategy — Stripe / Paystack / Flutterwave.
- **Messaging:** Kafka across process boundaries; Spring in-process events inside the monolith.
- **LLM:** OpenAI-compatible API via Spring AI.

## Repository layout

```
mercala/
├── pom.xml                 # parent POM (Spring Boot BOM, Java 21, module aggregator)
├── mercala-contracts/      # shared event + DTO records (published interface package)
└── mercala-core/           # the modular-monolith core
    └── src/main/java/com/mercala/
        ├── identity/  catalog/  inventory/  cart/  orders/
        ├── payments/  media/    platform/
        # (Spring Boot app + modules land in later milestones)
```

## Build

Requires JDK 21.

```bash
./mvnw clean install      # build + test all modules
```

## Roadmap

10 build phases (M0–M9) + a launch phase (M10), tracked in Linear. M0 = this foundation.
