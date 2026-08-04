# Mercala — roadmap

Living document. Tick boxes as things land. Edit freely; this is the working source of truth for "what's done, what's next." Linear mirrors it at the initiative level — see [CLAUDE.md](CLAUDE.md) for project IDs.

**Legend:** `[x]` done · `[~]` in progress · `[ ]` not started · `[?]` idea, not committed · `(opt)` optional polish

---

## M0 — foundation *(shipped 2026-06-07)*

One-line: the monorepo builds, the core app boots, local infra comes up.

- [x] Multi-module Maven monorepo + parent POM (Java 21, Spring Boot BOM)
- [x] `mercala-core` Spring Boot app + `/actuator/health`
- [x] Docker Compose local infra — ParadeDB (pg_search + pgvector), Kafka (KRaft), Redis, MinIO
- [x] Postgres + Flyway baseline
- [x] GitHub Actions CI (build + test)

## M1 — identity, tenancy & auth *(shipped 2026-07-01)*

One-line: tenants and users exist, and isolation is proven at two layers.

- [x] Tenant & User domain + migrations
- [x] Tenant signup + user registration endpoints
- [x] JWT issuance + login (Spring Security)
- [x] RBAC method security + role gates
- [x] Request-scoped tenant context + Hibernate tenant filter
- [x] Postgres Row-Level Security floor — raw-query bypass test cannot read another tenant's rows

## M2 — catalog & hybrid search *(shipped 2026-07-01)*

One-line: Postgres-native hybrid search, no Elasticsearch.

- [x] Catalog domain (Product, Variant, Category) + migrations
- [x] Product/Category CRUD REST API + validation
- [x] Lexical search — `pg_search` BM25
- [x] Semantic search — `pgvector` embeddings
- [x] Hybrid ranking — Reciprocal Rank Fusion
- [x] Event-driven index updates on product change (AFTER_COMMIT)

## M3 — inventory, cart & orders *(shipped 2026-07-02)*

- [x] Inventory domain + stock levels (+ RLS)
- [x] Stock reservation + release (oversell-safe)
- [x] Cart domain + add/update/remove lines
- [x] Checkout → order creation with idempotency key
- [x] `OrderPlaced` in-process event wiring
- [x] Order lifecycle states + transitions

## M4 — payments *(shipped 2026-07-02)*

- [x] `PaymentProvider` port + payments domain
- [x] Stripe adapter (Connect) + intent/capture
- [x] Paystack adapter
- [x] Flutterwave adapter
- [x] Provider selection per tenant/region
- [x] Webhook receiver + signature verify → `PaymentCaptured`
- [x] Idempotent payment handling + retries

## M5 — agent service *(shipped 2026-07-02)*

- [x] `mercala-agent` bootstrap + Spring AI
- [x] Agent tool/function definitions
- [x] Merchant chat-to-action (add product)
- [x] Shopper discovery agent over hybrid search
- [x] Agent Kafka wiring (`product.events` / `image.requests`)
- [x] Guardrails — tenant scoping, auth, rate limit, prompt-injection scanning

## M6 — image generation *(shipped 2026-07-02)*

- [x] `mercala-image-gen` worker + Kafka consumer
- [x] Image provider abstraction
- [x] MinIO/S3 storage + `image.results` producer
- [x] Media module consumes `image.results` → attaches to product

## M7 — eventing hardening *(shipped 2026-07-03)*

- [x] Kafka topic config (keys, partitions, consumer groups)
- [x] Transactional outbox for reliable publishing
- [x] Idempotent consumers + dead-letter handling
- [x] Replay test + `tenant_id` in all event headers

---

## Infra & deploy *(ongoing — component, not a phase)*

One-line: it runs on AWS, deploys itself, and holds no static credentials.

- [x] Structured logging + correlation IDs across all three services
- [x] Resilience4j on external calls (timeouts, retries, circuit breakers)
- [x] Layered Dockerfiles for core / agent / image-gen
- [x] CI/CD: build → test → containerize → push → provision → deploy
- [x] Cheap AWS spot host — Terraform (VPC, ECR, compute, S3) + Ansible
- [x] nginx reverse proxy + Let's Encrypt TLS
- [x] Terraform remote S3 backend (state survives runner sessions)
- [x] Unified Scalar API docs portal at `/api/v1/docs`
- [x] **Keyless deploys** — GitHub OIDC → `mercala-github-actions`, verified live 2026-08-02.
      No AWS access keys anywhere; the old key secrets are deleted. The OIDC provider is
      account-wide and shared with Voxtar, so this stack references it rather than owning it.
- [x] **Keyless runtime** — `mercala-app-host` instance profile for ECR pull + S3
- [x] **TLS survives reprovision** *(HAL-464)* — certs restored from and archived to S3, nginx
      degrades to HTTP instead of refusing to start when none exist, renewal cron scheduled
- [ ] Micrometer → Prometheus → Grafana *(HAL-165)*
- [ ] OpenTelemetry distributed tracing across core ↔ agent ↔ image-gen *(HAL-166)*
- [ ] Harden Compose + CI — image pinning, port binding, least-privilege permissions *(HAL-192)*
- [x] **Product imagery loads in a browser** *(HAL-425)* — images are served as presigned
      URLs minted per request, so the bucket stays entirely private. It holds the Terraform
      state, the Let's Encrypt archive, the nightly pg_dump and merchants' own uploads, and a
      public policy scoped to a prefix on it would have been one typo from publishing the
      database. image-gen also reads source photographs through the S3 client rather than
      anonymous HTTP — which is why enhancement had only ever worked on a laptop.
- [ ] Split media into its own public bucket, behind a CDN, with content-addressed keys
      *(HAL-577)* — blocked on a one-time local `terraform apply` in `devops/terraform/bootstrap`:
      the CI deploy role is scoped to one bucket by name and cannot create a second.
- [x] **`/api/media` is no longer public** *(HAL-495)* — a `permitAll` wildcard made
      `POST /api/media/replay`, a cross-tenant Kafka replay trigger, callable by anyone
      with no token. The wildcard is gone rather than narrowed, so anything added under
      that prefix is authenticated by default; replay itself is `PLATFORM_ADMIN` only.
- [ ] Verify the deploy host's SSH key *(HAL-460)* — `StrictHostKeyChecking=no` sends every
      secret to whatever answers on that IP
- [ ] Narrow the deploy role's `ec2:*` grant *(HAL-438)*
- [ ] (opt) Restrict SSH ingress from `0.0.0.0/0` to a known admin CIDR
- [ ] (opt) Remove empty `com.mercala.orders` / `com.mercala.payments` package stubs *(HAL-359)*

## Agent & AI *(ongoing — component, not a phase)*

One-line: the model layer is swappable and costs nothing to run by default.

- [x] Groq tool-calling format fix
- [x] JWT-delegated agent chat endpoints + GLM-4.7 routed via nginx
- [x] Local offline ONNX embedding model, zero-padded to 1536 dims
- [x] **Real provider adapters** — `cloudflare` / `replicate` / `openai` / `pollinations` /
      `placeholder`, one class each, selected and chained by `ImageProviderRouter`
- [x] Replicate provider — `Prefer: wait` fast path, polling fallback, per-model input map
- [x] Cloudflare Workers AI provider — free daily allowance, handles both the base64 JSON
      envelope (flux) and raw binary (SDXL) response shapes; now the default primary
- [x] Image format detection from magic bytes (providers return PNG *and* JPEG *and* WebP)
- [x] **Verified live** — `CLOUDFLARE_ACCOUNT_ID` + `CLOUDFLARE_API_TOKEN` set, real images
      generated through the Java provider (~230–260 KB, ~3s). Confirmed flux-1-schnell
      returns **JPEG**, not PNG, which is precisely why format sniffing exists.
      Opt-in `CloudflareImageProviderLiveTest` re-runs this whenever creds are present.
- [x] **AG-UI on the merchant agent** *(HAL-557)* — `POST /api/agent/merchant/agui` speaks the
      protocol: run lifecycle, streamed text, the full tool-call lifecycle *with results*,
      state snapshot plus JSON-Patch deltas, and `CUSTOM` frames for Mercala's own payloads.
      Tool events are emitted from inside the tool, not scraped from model chunks, so a
      failure is reported as one. The client owns the thread and sends it whole each run,
      which is what makes follow-ups and human-in-the-loop possible at all. `askUser`,
      `confirmAction` and `proposeEdit` ship server-side; tools the browser declares are
      registered per run and dispatched back to it. The old `/chat/stream` is untouched.
- [x] **Image enhancement** *(HAL-558)* — the merchant's own photograph in, a retouched one
      out. `ImageRequestEvent` grew a mode (additive, so replays of older events still read);
      `POST /api/media/uploads` stores originals under the tenant prefix, identified by magic
      bytes rather than by filename; Cloudflare (SDXL img2img), Replicate and OpenAI
      (`images/edits`) implement `enhanceImage`, and the router filters the chain by
      capability so "does not offer this" never opens a circuit breaker. Source URLs are
      fetched only from Mercala storage — an SSRF guard, because that URL originates in a
      chat message.
- [x] `GET /api/products/{id}/images` — imagery was write-only until now, so a merchant had
      no way to see what was generated or enhanced. Explicitly tenant-scoped in the query:
      the Hibernate filter only applies inside a transaction, which a controller read has
      not got.
- [ ] (opt) Add Replicate credit — account authenticates but returns **402 Insufficient
      credit**. Not urgent now that Cloudflare leads the chain.
- [ ] Decide: migrate embeddings off 1536-dim zero-padding to native ONNX size *(HAL-360)*
- [?] Per-tenant provider/model selection, the way payments already does it
- [?] AI product copy generation (descriptions, SEO) — the third agent surface

## Mercala Web *(next up)*

One-line: the frontend, chat-first on both sides.

**Decided 2026-08-02.** Separate `hallelx2/mercala-web` repo, Bun workspace (`apps/web` +
`packages/sdk`). Next.js App Router, server-first: `page.tsx` is a gate and loader, JWT in an
httpOnly cookie so server components can read it, `"use client"` only at interactive leaves.
SDK is generated **types** from the two OpenAPI specs plus a hand-written client — the contract
cannot drift, the ergonomics stay ours.

**Milestone 0 — API contract readiness** *(shipped 2026-08-02, PR #59)*
- [x] `securitySchemes` in the spec *(HAL-475)* — bearerAuth declared, public endpoints opted out
- [x] Agent chat streams over SSE *(HAL-476)* — typed frames, tenant context safe across
      the scheduler hop, three-layer timeout contract through nginx
- [x] `GET /api/orders` + `/{id}` *(HAL-477)* — role-scoped visibility, 404-not-403 for
      other shoppers' orders

**Milestone 1 — web foundation** *(target 2026-08-29)*
- [x] Create `mercala-web` repo — Bun workspace, CI green from a clean clone *(HAL-478)*
- [x] `@mercala/sdk` — generated types from both live specs + hand-written client *(HAL-479)*
- [x] Design-token system + Mercala brand *(HAL-170)* — token layer, DESIGN.md, primitives,
      landing + auth screens; dashboard's denser type register comes with HAL-172
- [x] Auth + tenant onboarding *(HAL-171)* — server actions, httpOnly cookie, gated
      /dashboard proving the pattern. **Live: https://mercala-web.vercel.app**

**Milestone 2 — merchant surface** *(target 2026-09-12)*
- [~] Chat-first merchant dashboard — add/manage products by chat, live imagery preview *(HAL-172)*
      — overview, chat, products, orders and settings pages shipped; streaming tool calls fixed
      (HAL-515, PR #64); imagery preview still open
- [x] **Agentic chat surface** *(HAL-559, HAL-560)* — AG-UI client in `@mercala/sdk` (typed
      events, SSE parser, run reducer, JSON Patch) plus a transcript that shows the work:
      tool cards with streamed arguments, results, durations and failures; a live activity
      rail fed by the agent's own state; and an event inspector for the raw log. Generative
      UI maps tools to controls — product cards, question cards, approve/reject, and an
      editable proposal whose corrections are what actually get applied.
- [x] **The composer** *(HAL-561)* — multiline with Enter/Shift+Enter, attachments by
      picker, drag or paste, a stop button that aborts the run, slash commands, and a field
      that stays live while the agent works.
- [x] **Image studio in chat** *(HAL-562)* — attach a photograph, watch it retouched, compare
      before and after on a slider. The result arrives out-of-band through Kafka, so the card
      polls the product's imagery rather than pretending the stream carries it.
- [x] **The overview is a place to work** *(HAL-576)* — an ask bar that hands its sentence to
      the agent, metrics that decompose into settled-versus-open, a fortnight of orders drawn
      from real dates, a "needs you" queue where every row links to the thing that fixes it,
      a best-seller ranking by units, and **Create a new store** as a card among the account's
      stores. A brand-new store gets a four-step checklist instead of four empty panels.
      The arithmetic lives outside the JSX with 31 tests, because "which orders count as
      revenue" is a product decision, not markup.
- [x] `createdAt` on `OrderResponse` *(HAL-575)* — the entity always had it and the response
      dropped it, so nothing time-shaped could be built. Exposing it surfaced a latent bug:
      the column is database-owned, so the order returned straight out of checkout claimed no
      date while its row had one.
- [x] Store profile — `tenants.description`, enriched `/auth/me`, `PATCH /api/tenants/me`,
      captured at signup and editable in settings *(PR #64)*
- [x] Identity-first signup *(HAL-552, PR #67)* — sign up as a person (name/email/password),
      name the store from a dashboard onboarding card; slugless login with password
      disambiguation; `POST /api/tenants/me` reissues the tenant-bearing token
- [x] Many stores, one account *(HAL-556, PR #69)* — store memberships, membership-gated
      `POST /api/auth/switch-store`, `/me.stores[]`; sidebar switcher, Stores page and
      new-store flow landing in the chat to add products

**Milestone 3 — shopper surface** *(target 2026-09-26)*
- [~] Storefront: chat-first discovery over hybrid search + product pages *(HAL-173)* —
      public API (`/api/public/stores/{slug}` + products/search, ACTIVE-only) and `/s/[slug]`
      browse + search + product pages shipped (PR #64); shopper chat still open
- [x] **The storefront has pictures** *(HAL-589)* — `ProductResponse` carries its images, each
      with a stable `url` and a presigned `viewUrl`, attached by a decorator at the edge so a
      page of twenty-four products costs one image query rather than twenty-four. Cards lead
      with the photograph; product pages get a gallery; a product without imagery gets a
      typographic tile rather than a broken frame, and so does one whose signature has
      expired. The merchant's own catalogue had the same blind spot and gets the same fix.
- [x] **A stranger can buy something** *(HAL-553)* — `POST /api/auth/guest` mints a session
      from a store slug alone, so cart and checkout stay `isAuthenticated()` rather than
      growing a second anonymous path through stock and money. Cart and order lines now
      carry the product's name, SKU, price and picture — a bag that showed variant UUIDs
      was not a bag — resolved in two batched queries per page rather than one per line.
      Storefront gets a buy control, a bag, and an order confirmation with its own URL.
      Tenant isolation is tested from the cheapest credential in the system: a guest of one
      store cannot add another store's variant or read its orders.
- [ ] Payment initiation *(HAL-593)* — nothing in the codebase asks a `PaymentProvider` to
      charge anyone; adapters and the inbound webhook exist, the call between them does not
- [ ] Order history for a shopper, and payment UI *(HAL-174)* — a guest's receipt has a URL
      but nothing lists their past orders

## Launch & GTM *(gated on Mercala Web — target 2026-10-15)*

- [ ] Positioning & messaging (one-liner, ICP, value props)
- [ ] Landing page with waitlist + demo CTA
- [ ] Seed a live demo tenant with real products
- [ ] 90-second product demo video
- [ ] README + docs + architecture diagram
- [ ] Product Hunt launch kit
- [ ] Launch posts — X thread, LinkedIn, dev.to
- [ ] Beta onboarding + feedback loop
- [ ] Pricing & plans page
- [ ] Product analytics + telemetry
