# Mercala

[![CI](https://github.com/hallelx2/mercala/actions/workflows/ci.yml/badge.svg)](https://github.com/hallelx2/mercala/actions/workflows/ci.yml)

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

## Local infrastructure

The project uses Docker Compose to run local infrastructure dependencies.

Start the services in the background:
```bash
docker compose up -d
```

Stop the services:
```bash
docker compose down
```

### Services

- **Postgres:** Port `5432` (user: `mercala`, password: `mercala`, db: `mercala`). Uses ParadeDB (pg_search, pgvector).
- **Kafka:** Port `9092` (single broker, KRaft mode).
- **Redis:** Port `6379`.
- **MinIO:** API Port `9000`, Console Port `9001` (user: `minioadmin`, password: `minioadmin`).

All services use named volumes for persistence.

## Running the whole stack locally

Three services and four containers. `docker compose up -d` brings up Postgres, Kafka,
Redis and MinIO; the services run on the host.

```bash
cp .env.example local.env     # then edit: it is gitignored
docker compose up -d
./mvnw -DskipTests install
scripts/dev-stack.sh all      # core, agent, image-gen — logs in .dev/
```

`local.env` is where ports and credentials live, so a machine already running something on
5432 or 8080 does not need the compose file edited. Two settings are worth knowing about:

- **`MERCALA_KAFKA_ENABLED=true`.** Core's consumers, topic creation and outbox relay are
  all behind this flag. Without it a generated image uploads to storage and never attaches
  to its product, and nothing in the logs says why.
- **`OPENAI_BASE_URL` / `OPENAI_COMPLETIONS_PATH` / `OPENAI_MODEL`.** Any OpenAI-compatible
  endpoint with tool calling will do. The path is configurable because not every provider
  serves `/v1/chat/completions` at the root of its base URL.

MinIO needs its two buckets once, and the public one needs anonymous read — that is where
finished product imagery is served from:

```bash
docker exec mercala-minio-1 sh -c 'mc alias set local http://localhost:9000 minioadmin minioadmin && mc mb -p local/mercala-images local/mercala-public-images && mc anonymous set download local/mercala-public-images'
```

The web app lives in the `mercala-web` repo and talks to one gateway address, so point it
at an nginx mirroring `devops/ansible/templates/nginx.conf.j2`: `/` to core, `/api/v1/agent`
rewritten to `/api/agent` on the agent, `/api/v1/images` to image-gen.

## Roadmap

10 build phases (M0–M9) + a launch phase (M10), tracked in Linear. M0 = this foundation.
