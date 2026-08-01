# Mercala

Agent-native, multi-tenant e-commerce platform. Java 21 · Spring Boot · Postgres (ParadeDB) · Kafka · Spring AI.

## Planning

[ROADMAP.md](ROADMAP.md) is the working source of truth — a living checkbox document. Tick boxes there as work lands, in the same commit as the work. Linear mirrors it at the initiative/project level for status and dates; do not let the two drift.

Load the `linear-board` skill before any planning work.

- **Initiative:** Mercala — `1fdf4b00-c11b-4984-beb0-1fe632c499b2`
- **Team:** Hallelx2 — `7becb1fa-eca9-4c11-befa-19f64d7f9497` (issue prefix `HAL-`)
- **Repo:** `hallelx2/mercala` · base branch `main`

### Active projects

| Project | ID | State |
|---|---|---|
| Mercala Web | `70a7ea5f-dcb0-4d11-a3dc-4363e5bb0748` | **In Progress** — the current push |
| Mercala Infra & Deploy | `5e6ae27d-486a-4781-8e1f-e7ed1d4dccd4` | In Progress — observability still open |
| Mercala Agent & AI | `2a76cdca-67af-489a-b520-e19274ce4da7` | Backlog |
| Mercala Launch & GTM | `0aca1923-d473-4c6c-9e61-688e5d7bd094` | Planned — gated on Web |

### Closed history

M0–M7 are Completed projects covering the backend build-out (HAL-119 → HAL-167, shipped 2026-06-06 → 2026-07-03). They are history — do not reopen or file new work against them. M8 was retired on 2026-08-01; its issues live in Mercala Infra & Deploy.

Projects are **components**, not phases. New work goes to the component project that owns it, as an issue against a milestone. Do not create a new project per phase — that is the mistake the M0–M10 layout made, and it is why the board went stale.

## Architecture

- **Topology:** monorepo, modular-monolith core (`mercala-core`) plus two carved-out services (`mercala-agent`, `mercala-image-gen`) and shared `mercala-contracts`
- **Multi-tenancy:** shared DB + `tenant_id`; defense in depth = RBAC → Hibernate tenant filter → Postgres RLS. Never weaken a layer without a test proving the others still hold.
- **Search:** Postgres-native hybrid — `pg_search` BM25 + `pgvector` semantic, fused with RRF. No Elasticsearch.
- **Embeddings:** local offline ONNX model, zero-padded to 1536 dims for column compatibility (see HAL-357, HAL-360)
- **Payments:** `PaymentProvider` strategy — Stripe / Paystack / Flutterwave, selected per tenant region
- **Messaging:** Kafka across process boundaries with transactional outbox, idempotent consumers, DLQ; Spring in-process events inside the monolith
- **LLM:** OpenAI-compatible API via Spring AI; provider is swappable (currently GLM-4.7 via nginx)

Real code lives in `com.mercala.order` and `com.mercala.payment` — **singular**. The plural `orders`/`payments` packages are empty scaffolding stubs slated for deletion (HAL-359); do not add files there.

## Build

Requires JDK 21.

```bash
./mvnw clean install
```

Local infra via `docker compose up -d` — Postgres 5432, Kafka 9092, Redis 6379, MinIO 9000/9001.

## Deploy

`.github/workflows/deploy.yml` runs build → test → containerize → provision → deploy on push to `main`. Infrastructure is Terraform (`devops/terraform/`) with remote S3 state; configuration is Ansible (`devops/ansible/`). Target is a cheap AWS spot host behind nginx with Let's Encrypt TLS.

**There are no AWS access keys, anywhere. Do not reintroduce them.**

- **CI → AWS:** GitHub OIDC. The workflow assumes `mercala-github-actions` via `role-to-assume`, scoped by an STS trust condition to `repo:hallelx2/mercala:ref:refs/heads/main`. The only thing in GitHub is `vars.AWS_DEPLOY_ROLE_ARN`, which is not a secret.
- **Host → AWS:** the `mercala-app-host` EC2 instance profile. The AWS CLI and the S3 client resolve credentials from instance metadata, so nothing is injected into containers and nothing expires.
- **One-time setup:** `devops/terraform/bootstrap/` creates the OIDC provider and the deploy role. Apply it once, locally, with your own credentials. Its state is local and gitignored.

If a deploy fails on credentials, fix the role or the trust policy — never fall back to minting a key.

Never commit `devops/*.pem`, any `*.tfstate*`, `.env`, or `devops/deploy.sh` — all are gitignored and must stay that way.

## Image generation

`mercala-image-gen` selects a provider via `mercala.image-gen.provider` and degrades through `mercala.image-gen.fallback-chain`. One class per backend (`CloudflareImageProvider`, `ReplicateImageProvider`, `OpenAiImageProvider`, `PollinationsImageProvider`, `PlaceholderImageProvider`); `ImageProviderRouter` owns selection, fallback, and per-provider circuit breaking.

Default chain is `cloudflare → replicate → pollinations → placeholder`. Cloudflare Workers AI leads because it runs on a recurring daily free allowance rather than prepaid credit, so the default configuration costs nothing. Workers AI response shape varies by model family — `flux-1-schnell` returns base64 inside the JSON envelope, the SDXL models return raw bytes — so `CloudflareImageProvider` branches on content type, not on model name.

Adding a provider means adding a class that implements `ImageProvider` — never adding a branch to an existing one. Providers throw on failure; they must not substitute another backend's result.

Replicate model inputs are an open map (`mercala.image-gen.replicate.input.*`) because Replicate models do not share an input schema — `flux-schnell` takes `aspect_ratio` and has no `width`/`height`. Changing model means changing config, not code.

Providers return different formats (PNG, JPEG, WebP). `ImageFormat.detect` sniffs magic bytes and sets the object extension and content type accordingly — do not hardcode `.png`.
