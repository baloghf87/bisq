# Feasibility study: headless orderbook + trades API node

**Status:** Feasibility study (no implementation)
**Question:** Can we run an autonomous, headless node that connects to the Bisq
network, derives a live orderbook and a trades tape / candlestick feed for all
markets, and exposes it via a REST/WS API — packaged as a Docker image suitable
for Kubernetes? Historical backfill is *not* required; a current snapshot is
enough.

**Verdict: Feasible, and most of it already exists in this repository.** The
data is available on the P2P network to any passively-connected node, and two
existing modules (`statsnode` and `restapi`) already implement the two halves
we need. The remaining work is integration plus some net-new analytics
(candles/ticker/depth) and packaging — not new protocol or networking work.

---

## 1. What the network actually exposes

Everything we need is replicated to every node through the Bisq P2P storage
layer, so a read-only node that just *connects and listens* obtains it without
trading, without a wallet, and without any special privileges.

### 1.1 Orderbook (open offers) — live snapshot, by design

- Offers are `OfferPayload` (`core/.../offer/bisq_v1/OfferPayload.java`), a
  `ProtectedStoragePayload` + `ExpirablePayload` with a **9-minute TTL**
  (`OfferPayloadBase.TTL`). The maker must refresh the entry every <9 minutes;
  when the maker goes offline the offer disappears from the network ~9 minutes
  later.
- Consequence: the network only ever holds the **currently-open** offers. There
  is no historical offer archive to fetch. This matches the requirement exactly
  — a "current snapshot" is the *only* thing offers can be.
- Access: `OfferBookService.getOffers()` returns the full current set from the
  P2P data map; `getOfferForJsonList()` returns a ready-to-serialize view. A
  `HashMapChangedListener` delivers add/remove/refresh events live after
  bootstrap. An orderbook and a depth chart are a straight aggregation of this
  set (group by market + direction, sort by price, cumulate size).

### 1.2 Trades tape / candlesticks — full history available

- Executed trades are published as `TradeStatistics3`
  (`core/.../trade/statistics/TradeStatistics3.java`): an **append-only**
  `PersistableNetworkPayload` with **no TTL and no deletion**. Each is ~50 bytes
  (currency, price, BTC amount, payment method, date, truncated
  mediator/refund-agent addresses).
- A fresh node obtains the accumulated set on bootstrap from two sources merged
  by `HistoricalDataStoreService`: (a) version-tagged **historical resource
  files bundled in the app jar**, plus (b) the **live delta** synced from seed
  nodes since the node's version. New trades then arrive live via an
  `AppendOnlyDataStoreListener`. So even though we only need a snapshot, we get
  effectively the complete trade history for free.
- Access: `TradeStatisticsManager.getObservableTradeStatisticsSet()` (live set)
  and `getTradeStatisticsList(dateStart, dateEnd)` (range query, already used by
  the existing REST endpoint). Candlesticks/HLOC and a trades tape are computed
  by bucketing this set per market and time interval.

### 1.3 Data limitations to be aware of (not blockers)

- **Only the seller publishes** a trade statistic, and trade amounts are
  privacy-reduced (rounded; entries after 2021-11-01 are subject to validity
  filters incl. a 2 BTC historical cap in `isValid()`). This is fine for market
  analytics but means figures are approximate, not an audited ledger.
- **No counterparty identity / volume-per-maker** is derivable — by design.
- On bootstrap, large responses are truncated and re-requested up to 30 times
  until complete (`RequestDataManager`, `DateSortedTruncatablePayload`,
  `tradeStatistics3MaxItems` default 15,000 per response). This is automatic;
  the node just needs to stay connected through it.

---

## 2. Building blocks that already exist here

| Piece | Where | Reuse |
|---|---|---|
| Headless P2P-only node, **no wallet**, no GUI | `statsnode` (`Statistics`, `StatisticsMain`) via `ExecutableForAppWithP2p` + `ModuleForAppWithP2p` + `AppSetupWithP2PAndDAO` | This is exactly the "connect and collect offers + trade stats" node. It pins `OfferBookService` + `TradeStatisticsManager` + `PriceFeedService`. |
| REST/JSON server + Swagger | `restapi` (`RestApiMain`, `RestApi`) — Jersey/JAX-RS on the JDK `HttpServer`, `io.swagger.v3` docs at `/doc/v1/` | The HTTP layer to reuse verbatim. |
| **The exact market endpoints** | `restapi/.../endpoints/ExplorerMarketsApi.java` | `GET /explorer/markets/get-offers` (live orderbook), `GET /explorer/markets/get-trades/{newest}/{oldest}` (trades), `GET /explorer/markets/get-currencies`. Depends **only** on `OfferBookService` + `TradeStatisticsManager` — no DAO, no bitcoind. |
| Docker image build | `build-logic/docker-image-builder` `DockerImageBuilderPlugin` (distroless `java11`), already applied in `seednode/build.gradle` | One-line plugin apply gives a new module a container image (template needs minor generalization — see §5). |
| gRPC alternative | `daemon` + `proto-grpc/.../grpc_services.proto` | Not recommended here: gRPC `Offers`/`Trades` are *wallet-centric* (your own offers/trades), needs a full wallet node, and has **no** public trade-statistics endpoint. |

**Key finding:** the `restapi` module already serves a JSON orderbook and trades
feed. The only reason it is heavier than we need is that `RestApi extends
ExecutableForAppWithP2p` and its *other* endpoints run it as a **full DAO node**
(requires bitcoind RPC + blocknotify). The market endpoints themselves need
none of that.

---

## 3. Recommended architecture

Build a small **P2P-only "market data" node** = `statsnode`'s data collection
(no wallet, **no DAO/bitcoind**) + `restapi`'s Jersey server hosting a markets
resource. Concretely, a new gradle module (e.g. `marketsnode`) or an added
endpoint set on a trimmed `restapi`:

```
                 Bisq P2P network (Tor, mainnet)
                          │  (read-only, passive)
        ┌─────────────────▼──────────────────┐
        │  Headless node (ExecutableForAppW…) │
        │   • P2PService  (bootstrap+listen)  │
        │   • OfferBookService     → offers   │
        │   • TradeStatisticsManager → trades │
        │   • PriceFeedService (optional)     │
        └─────────────────┬──────────────────┘
                          │ in-process
        ┌─────────────────▼──────────────────┐
        │  Jersey/JAX-RS HTTP server          │
        │   REST:  /markets /offers /depth    │
        │          /trades /candles /ticker   │
        │   WS/SSE: live offer + trade stream  │
        │   Swagger at /doc                    │
        └──────────────────────────────────────┘
```

**Drop the DAO.** For offers + trades we do not need `DaoSetup`, bitcoind RPC,
or any wallet. This removes the heaviest and most operationally fragile
dependency and makes the k8s deployment far simpler. (Note: `ExecutableForAppWithP2p.startApplication()`
currently forces `useFullModeDaoMonitor = true`; a market-only node should not
start `DaoSetup` at all — this is the main code decision, see §6.)

### API surface (proposal)

Reuse-first (already implemented → wrap/rename): `get-offers`, `get-trades`,
`get-currencies`. Net-new aggregation on top of the same in-memory data:

- `GET /markets` — list of active markets/pairs
- `GET /orderbook/{market}` — bids/asks (derived from live offers)
- `GET /depth/{market}` — cumulative depth
- `GET /trades/{market}` — trades tape (paged)
- `GET /candles/{market}?interval=1h` — HLOC/OHLCV buckets
- `GET /ticker` — last/high/low/volume per market
- `WS /stream` (or SSE) — live offer add/remove + new trades

The classic public Bisq markets API (`/api/ticker`, `/api/hloc`, `/api/depth`)
is a **separate external project** and is **not** in this repo; the aggregation
for candles/ticker/depth is the main net-new logic (straightforward map/reduce
over `TradeStatisticsManager` + `OfferBookService`).

---

## 4. Transport / networking (the real operational constraint)

- **Tor is mandatory on BTC mainnet.** `useLocalhostForP2P` is forced `false` on
  mainnet (`Config`), all seed nodes are `*.onion:8000`, and the node always
  uses `TorNetworkNode`. Clearnet only works on regtest/testnet. So the k8s pod
  must have working outbound Tor.
- Two options: (a) **embedded Tor** — Bisq's `NewTor` mode bundles a Tor binary
  and starts it in-process; simplest topology but needs the tor binary present
  in the image (distroless has no shell/libs — a risk to validate, see §5/§7);
  (b) **external Tor** via control port (`RunningTor`) — run a Tor **sidecar
  container** in the pod and point the node at it. For k8s, the sidecar is the
  cleaner, more observable choice.
- **Bootstrap latency:** connecting over Tor + pulling the full data set takes
  on the order of minutes on first start. The node is only "ready" after
  `onDataReceived`/`onUpdatedDataReceived`. Use that as the readiness signal.
- **No inbound required** for data collection. The node can publish a Tor hidden
  service (as the base executable does) but it doesn't need to accept inbound
  connections to read data; this can likely be minimized.

---

## 5. The Docker / Kubernetes deliverable

- **Image:** reuse `DockerImageBuilderPlugin`. Today its template hardcodes
  `/seednode/…` paths, `bisq/seednode:latest`, and `gcr.io/distroless/java11`.
  For a new module we'd generalize the paths and confirm the JDK (the codebase
  targets JDK 21 per `AGENTS.md`, but this docker template still pins Java 11 —
  needs reconciling).
- **Tor in-image vs sidecar:** distroless has no package manager/shell, so the
  cleanest k8s design is a 2-container pod: `marketsnode` + `tor` sidecar
  (there's already a `bisq/tor` image target in the plugin and tor Dockerfiles
  under `seednode/deployment_v2/docker/`). Alternatively switch the base image
  to one that can host embedded Tor.
- **State/volume:** a `PersistentVolumeClaim` is recommended (not strictly
  required) to persist the P2P data store, bundled/derived trade-stats files,
  and the Tor identity across restarts — this cuts re-bootstrap time. A fresh
  ephemeral pod also works, it just re-syncs each start.
- **Resources:** `seednode` pins `-Xms/-Xmx 4096M`. A market-only node holds the
  offer map + trade-stats set (tens of thousands of ~50-byte entries + bundled
  history) — start around **2–4 GB** heap and tune down.
- **Probes:** readiness = P2P bootstrap complete + first data received;
  liveness = HTTP server responding. Note the base executable has a **built-in
  24h periodic self-shutdown** (`SHUTDOWN_INTERVAL`) intended for restart-by-
  supervisor; in k8s a Deployment restart policy handles that, but the behavior
  (`preventPeriodicShutdownAtSeedNode`) must be configured deliberately.
- **Scaling:** this is a read-only cache of network state; scale horizontally
  behind a Service, or run one collector + a stateless API tier. Each replica
  bootstraps independently over Tor.

---

## 6. Effort estimate & phasing

- **Phase 1 — MVP (small):** new/trimmed P2P-only module (statsnode-style, DAO
  removed) + Jersey server hosting the already-written `get-offers` /
  `get-trades` / `get-currencies` logic + Docker image + Tor sidecar. This is
  mostly wiring existing code. Deliverable: a container that autonomously
  connects and serves the raw orderbook + trades snapshot.
- **Phase 2 — analytics (moderate):** `/orderbook`, `/depth`, `/candles`,
  `/ticker` aggregation, and a WS/SSE live stream. Net-new but self-contained
  computation over in-memory data.
- **Phase 3 — hardening:** persistence/volume, probes, resource tuning, metrics,
  and validating embedded-vs-sidecar Tor under load.

---

## 7. Risks / open questions (need decisions before implementation)

1. **DAO removal.** Cleanly running `ExecutableForAppWithP2p` *without* starting
   `DaoSetup` (and without `useFullModeDaoMonitor`) needs verification — some
   services may assume DAO presence. If it turns out entangled, the fallback is
   to keep `statsnode` as-is (which tolerates a lite DAO) and just add the HTTP
   layer, accepting a bit more baggage. **Decision: confirm the P2P-only path or
   accept statsnode's footprint.**
2. **Tor packaging.** Embedded Tor in a distroless image vs a Tor sidecar. This
   is the biggest operational unknown. **Recommendation: sidecar for k8s.**
3. **New module vs new dependency.** `AGENTS.md` says don't add dependencies
   without strong justification. WS support isn't currently a dependency; SSE
   via Jersey (already present) avoids a new lib. **Decision: SSE (no new dep)
   vs a WS library.**
4. **New gradle module vs extending `restapi`.** A dedicated `marketsnode`
   module keeps concerns clean; extending `restapi` reuses more but drags DAO
   assumptions. **Decision needed.**
5. **Mainnet vs testnet target** for the deliverable.
6. **Licensing.** Bisq is AGPL-3.0. Exposing a (possibly modified) node as a
   network service triggers AGPL's obligation to offer corresponding source to
   users of that service. Confirm this is acceptable for the intended
   deployment.
7. **Data fidelity expectations.** Trade figures are privacy-reduced/rounded and
   seller-reported; confirm that's acceptable for the consuming use case.

---

## 8. Bottom line

Deriving a live orderbook and a trades/candlestick feed for all markets from a
headless, autonomously-connecting node and exposing it over HTTP is **clearly
feasible and largely pre-built here**. The current-snapshot scope aligns
naturally with how the data lives on the network (offers are inherently a live
snapshot; trade statistics come down in full on bootstrap). The genuine
engineering is: (1) trimming an existing headless node to a P2P-only, wallet-
free, DAO-free market collector, (2) wrapping it with the existing Jersey
endpoints plus new candle/depth/ticker aggregation, and (3) packaging it with
Tor for Kubernetes. No new networking or protocol work is required.
