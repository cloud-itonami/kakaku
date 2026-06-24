# etzhayyim-project-kakaku

kakaku.etzhayyim.com — 商品 x ECサイト x 価格更新の比較サイト actor。canonical product と merchant-specific offer を分離し、価格・送料・在庫・配送条件を時系列で管理する。

## Architecture

```
Browser / API client
  → /xrpc/com.etzhayyim.apps.kakaku.*
    ↓
  did:web:kakaku.etzhayyim.com
    ├─ Catalog: registerProduct / updateProduct / getProduct
    ├─ Merchant Registry: registerMerchant / updateMerchant / getMerchant
    ├─ Offer Ingest: upsertOffer / ingestOfferFromUrl / archiveOffer / ingestMerchantFeed
    ├─ Matcher: createMatchCandidate / resolveMatch
    ├─ Price Monitor: appendPriceHistory / detectPriceDrop
    └─ Ranking: compareOffers / getLowestOffer / getPriceHistory
```

## Data Model

価格は `product` の属性ではない。価格は `merchant` ごとの `offer` に属し、更新ごとに `priceHistory` を追加する。

### Collections

| Collection | Role | Key fields |
|---|---|---|
| `com.etzhayyim.apps.kakaku.product` | canonical product | `productId, name, brand, model, jan, gtin, mpn, category` |
| `com.etzhayyim.apps.kakaku.merchant` | EC site / seller registry | `merchantId, name, domain, reputationScore, shippingPolicy, selectorProfile, selectorVersion, selectorRollout, status` |
| `com.etzhayyim.apps.kakaku.selectorRevision` | selector version history | `revisionId, merchantId, selectorProfile, selectorVersion, selectorConfig, rollout, isActive` |
| `com.etzhayyim.apps.kakaku.offer` | current merchant offer | `offerId, productId, merchantId, price, shippingFee, totalPrice, availability, deliveryEta, productUrl, observedAt` |
| `com.etzhayyim.apps.kakaku.priceHistory` | offer time series | `productId, merchantId, offerId, price, totalPrice, availability, observedAt` |
| `com.etzhayyim.apps.kakaku.matchCandidate` | source to canonical match queue | `sourceMerchantId, sourceSku, productId, confidence, status` |

## Actor Composition

```
did:web:kakaku.etzhayyim.com
did:web:kakaku.etzhayyim.com:actor:catalog
did:web:kakaku.etzhayyim.com:actor:merchantRegistry
did:web:kakaku.etzhayyim.com:actor:offerIngest
did:web:kakaku.etzhayyim.com:actor:matcher
did:web:kakaku.etzhayyim.com:actor:priceMonitor
did:web:kakaku.etzhayyim.com:actor:ranking
```

## Path Resolve

entity は path-based DID で解決する。controller は `did:web:kakaku.etzhayyim.com`。

### DID Patterns

| Entity | DID pattern | Notes |
|---|---|---|
| Product | `did:web:kakaku.etzhayyim.com:product:{product_key}` | canonical product |
| Merchant | `did:web:kakaku.etzhayyim.com:merchant:{merchant_key}` | EC site / seller |
| Offer | `did:web:kakaku.etzhayyim.com:offer:{merchant_key}:{offer_key}` | merchant-scoped current offer |
| Match candidate | `did:web:kakaku.etzhayyim.com:match:{merchant_key}:{source_sku}` | source listing reconciliation |

### Resolution Priority

`product_key` は次の優先順で決める:

1. `jan`
2. `gtin`
3. `mpn`
4. `brand + model`
5. normalized title hash

`merchant_key` は:

1. normalized apex domain
2. explicit `merchantId`

`offer_key` は:

1. merchant native offer id
2. merchant SKU
3. normalized product URL hash

### Normalization Rules

- lowercase
- ASCII slug preferred
- spaces and `/` become `_`
- unstable query params are excluded from URL-derived keys
- same merchant + same native offer id must resolve to the same offer DID
- same JAN/GTIN must resolve to the same product DID unless explicitly split by pack size

### Examples

| Input | Resolved DID |
|---|---|
| JAN `4901777300443` | `did:web:kakaku.etzhayyim.com:product:jan_4901777300443` |
| Merchant `www.yodobashi.com` | `did:web:kakaku.etzhayyim.com:merchant:yodobashi_com` |
| Offer `merchant=yodobashi_com, sku=4549995501234` | `did:web:kakaku.etzhayyim.com:offer:yodobashi_com:4549995501234` |

## Update Flow

1. `merchantRegistry` registers a merchant and crawl policy.
   `merchant` record は `selectorProfile` / `selectorVersion` / `selectorConfig` / `selectorRollout` を持てる。domain 既知なら preset が自動投入され、merchant 単位で override できる。
   変更ごとに `selectorRevision` を append し、`activateSelectorRevision` で active version を切り替える。`selectorRollout < 1` の場合、`merchantId + productUrl` の安定 hash で canary sampling し、path は `active` または `previous` のどちらかに割り当てる。
2. `offerIngest` upserts merchant offers from feeds, APIs, or page observations.
   `ingestOfferFromUrl` は merchant-specific selector / JSON-LD / regex-title 抽出を先に使い、欠損時だけ Murakumo LLM で `name / price / currency / availability` を JSON 抽出する。
3. `matcher` binds source offers to a canonical product via JAN, GTIN, MPN, brand, model, and normalized title.
4. `priceMonitor` appends `priceHistory` and checks lowest-price deltas.
5. `ranking` returns cheapest and best-overall offers using total price, availability, ETA, and trust.

## Graph Labels

| Label | Meaning |
|---|---|
| `KakakuProduct` | canonical product |
| `KakakuMerchant` | merchant / EC site |
| `KakakuOffer` | current merchant offer |
| `KakakuPriceHistory` | historical price point |
| `KakakuMatchCandidate` | unresolved or approved product match |

## Ranking Rules

- `cheapest`: minimum `total_price`
- `bestOverall`: weighted by `total_price`, `availability`, `delivery_eta`, `merchant.reputation_score`
- `suspicious`: unusually low price, inactive merchant, missing stock state, or broken source URL

## Autonomous heartbeat (clj-native)

- `py/autorun.clj` (+ `py/kotoba.clj`) — **clj-native SSoT** (ADR-2606142300 D1: new logic-core
  authored in Clojure, no Python twin). The autonomous price-intel loop: each cycle observes the
  OFFLINE product/merchant/offer/priceHistory snapshot (`kotoba/seed.edn`, real-EDN, read via
  `clojure.edn`) → a small adapter builds the handler `state` → derives the cross-merchant price
  SPREAD (`agent/handle-arbitrage`), the bounded supply/demand index (`agent/handle-supply-demand`),
  and the present-interest proxy (`agent/handle-demand`) → **persists one content-addressed
  transaction** of `:kakaku.obs/*` + `:kakaku.region/*` observations to the append-only **local**
  kotoba Datom log (`py/kotoba.clj`), linking the previous CID into a verifiable commit-DAG.
  Deterministic / resume-safe (cycle drives tx-id + as-of; fixed snapshot stamp); NO external I/O.
  **G2 holds by construction**: only price-DIFFERENCE + supply/demand OBSERVATIONS are
  representable — no buy/sell signal / price target / forecast (forecasting is mitooshi's job);
  every derived datom carries `:sourcing :synthesized`; `:intent` is `buyer-transparency+supply-
  resilience`. The LIVE offer ingest (page fetch, `ingest.clj`) + the live social post stay
  operator-gated (G11, no-server-key) — the loop reads a LOCAL snapshot only. Invariants in
  `py/test_autorun.clj` (persist, commit-DAG verify, determinism, tamper-detect, G2 non-speculative,
  G5 :synthesized, append-only, frozen golden head-CID).

  ```bash
  bb -cp 20-actors -e "(require 'kakaku.py.autorun)(apply kakaku.py.autorun/-main [\"--cycles\" \"3\" \"--fresh\"])"
  ```

## Notes

- `offer` is mutable current state.
- `priceHistory` is append-only observation history.
- `merchant` and `product` are first-class entities and should not be folded into `offer`.
- Cross-site comparison should rank on landed price (`price + shippingFee`), not sticker price alone.
