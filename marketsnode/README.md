# marketsnode

A **headless Bisq node** that connects to the Bisq P2P network and exposes the **current order
book** and **trade statistics** of **all markets** as a read-only JSON HTTP API — packaged as a
single Docker image you can run in Kubernetes. It connects autonomously (over its own bundled Tor)
and needs no Bitcoin wallet, no GUI, and no manual interaction.

This module was built to answer the question: *"get liquidity and historical-trades information out
of the network — derive an order book and a trades tape / candlestick chart for all markets and
expose it via a REST API, as a container that autonomously connects to the network."* The scope is
a **current snapshot** (no historical backfill required).

- Full feasibility analysis: [`../docs/feasibility-orderbook-trades-api.md`](../docs/feasibility-orderbook-trades-api.md)
- Quick jump: [How to test against the main network](#how-to-test-against-the-main-network)

---

## 1. What the network actually exposes (and what this node does with it)

Everything served here is replicated to every Bisq node through the P2P storage layer, so a
read-only node that just *connects and listens* obtains it without trading and without a wallet.

| Data | Network representation | Availability | This node derives |
|---|---|---|---|
| **Open offers** | `OfferPayload` — a `ProtectedStoragePayload` / `ExpirablePayload` with a **9-minute TTL** (maker must refresh; offer vanishes ~9 min after the maker goes offline) | **Live snapshot only** — the network never holds a historical offer archive, which matches the "current snapshot" scope | order book, cumulative depth, best bid/ask |
| **Executed trades** | `TradeStatistics3` — an **append-only** `PersistableNetworkPayload` (no TTL, never removed) | A fresh node downloads the **full accumulated set** on bootstrap (bundled historical resource files + live delta from seed nodes), then receives new ones live | trades tape, OHLCV candles, 24h ticker |

### Data fidelity — read this before trusting the numbers

The trade data is intentionally privacy-preserving, so treat it as **market analytics, not an
authoritative settlement ledger**:

- **Seller-reported, one record per trade.** Since Bisq v1.4.0 only the *seller* publishes a
  `TradeStatistics3` (the buyer stopped, to avoid duplicates). If a seller's client is offline at
  completion or never republishes, that trade can be absent — coverage is best-effort, not
  guaranteed-complete.
- **Amounts are rounded.** For fiat trades the BTC amount is snapped so the fiat volume lands on a
  whole unit and the amount keeps 4 decimals — close to, but not the exact satoshi value that
  settled on-chain (and excludes fees/deposits).
- **No counterparty identity.** A record carries only currency, price, amount, payment method,
  date, and a *truncated* (first 4 chars) mediator/refund-agent address. There is no maker/taker
  identity, so you **cannot** attribute trades to a party or compute per-maker volume. (Open
  *offers*, by contrast, do carry the maker's onion node address — that's inherent to offers.)

### Transport

On **BTC mainnet, Tor is mandatory** — all seed nodes are `.onion` addresses and clearnet is
disabled. The Docker image starts its **own bundled Tor** (no sidecar required). Clearnet is only
possible on regtest/testnet.

---

## 2. Architecture

`marketsnode` reuses two proven patterns already in this repository:

- the **`statsnode`** data-collection setup — `ExecutableForAppWithP2p` + `ModuleForAppWithP2p` +
  `AppSetupWithP2PAndDAO`: a P2P-only node with **no wallet and no GUI**, pinning
  `OfferBookService`, `TradeStatisticsManager` and `PriceFeedService`;
- the **`restapi`** module's approach of serving Bisq network data over HTTP (its
  `ExplorerMarketsApi` already exposed `get-offers`/`get-trades` and depends only on
  `OfferBookService` + `TradeStatisticsManager`).

```
                 Bisq P2P network (Tor, mainnet)
                          │  read-only, passive
        ┌─────────────────▼──────────────────┐
        │  MarketsNodeMain / MarketsNode      │
        │   ExecutableForAppWithP2p (no wallet, no GUI)
        │   • P2PService  (bootstrap + listen)│
        │   • OfferBookService      → offers  │
        │   • TradeStatisticsManager → trades │
        │   • PriceFeedService (market prices)│
        └─────────────────┬──────────────────┘
                          │ in-process
        ┌─────────────────▼──────────────────┐
        │  MarketDataService  (aggregation)   │
        │   order book · depth · trades ·     │
        │   candles · ticker                  │
        └─────────────────┬──────────────────┘
                          │
        ┌─────────────────▼──────────────────┐
        │  MarketsHttpServer                  │
        │   JDK built-in HttpServer, /api/v1  │
        │   (no external web framework)       │
        └──────────────────────────────────────┘
```

### File map

| File | Responsibility |
|---|---|
| `src/main/java/bisq/marketsnode/MarketsNodeMain.java` | Entry point; extends `ExecutableForAppWithP2p` (mirrors `StatisticsMain`). |
| `src/main/java/bisq/marketsnode/MarketsNode.java` | Pins the P2P services, starts the price feed on bootstrap, starts/stops the HTTP server. |
| `src/main/java/bisq/marketsnode/market/MarketDataService.java` | All aggregation: order book, depth, trades, candles, ticker; unit normalisation; defensive snapshots. |
| `src/main/java/bisq/marketsnode/market/CandleAccumulator.java` | Accumulates trades into one OHLCV candle. |
| `src/main/java/bisq/marketsnode/http/MarketsHttpServer.java` | JDK `HttpServer` routing + JSON responses + `/api/v1/ready`. |
| `src/main/java/bisq/marketsnode/dto/MarketDtos.java` | Plain JSON data holders. |
| `Dockerfile` | Self-contained multi-stage build; JRE 21; bundled Tor; non-root; `/data` volume. |
| `k8s/marketsnode.yaml` | Deployment + Service + PVC with startup/liveness/readiness probes. |
| `build.gradle`, `../settings.gradle` | Module definition and registration. |

**Design decision — the DAO footprint.** `marketsnode` keeps the same application setup as
`statsnode`, which runs as a DAO *lite* node (state synced over P2P; no local bitcoind required).
The order-book/trades endpoints themselves depend only on the two P2P services above. Stripping the
DAO entirely was deliberately *not* done, to stay on the proven, low-risk `statsnode` path. See the
feasibility study's "open questions" for the trade-offs.

---

## 3. HTTP API reference

Base path `/api/v1`. All endpoints are `GET` and return JSON. `Access-Control-Allow-Origin: *` is
set so browsers can call it directly.

### Units and market keys

- **Market key** is `BASE_COUNTER`: fiat markets are `BTC_<FIAT>` (e.g. `BTC_EUR`), altcoin markets
  are `<ALT>_BTC` (e.g. `XMR_BTC`). The `market` parameter is case-insensitive and also accepts the
  slash form (`BTC/EUR`).
- **`price`** and **`volume`** are decimal numbers in the **counter** currency (EUR for `BTC_EUR`,
  BTC for `XMR_BTC`); **`amount`** is in the **base** currency (BTC for `BTC_EUR`, XMR for
  `XMR_BTC`). Values are normalised from Bisq's internal scaled integers.

### Endpoints

| Endpoint | Description |
|---|---|
| `GET /` | Liveness — returns the same body as `/status`. |
| `GET /api/v1/status` | `bootstrapped`, `numOffers`, `numTradeStatistics`, `numMarkets`, `version`, `timestamp`. |
| `GET /api/v1/ready` | Readiness — HTTP **200** `{"ready":true}` once the P2P network is bootstrapped, **503** while still syncing. |
| `GET /api/v1/currencies` | Supported fiat and crypto currencies (`code`, `name`, `type`). |
| `GET /api/v1/markets` | Active markets (pairs that currently have offers or trade statistics). |
| `GET /api/v1/offers[?market=]` | Raw open offers in Bisq's native offer-JSON shape (rich fields; `primaryMarket*` values are scaled by 1e8). Optional `market` filter. |
| `GET /api/v1/orderbook?market=BTC_EUR` | Order book: `buys` (bids, highest price first) and `sells` (asks, lowest price first), each entry `{price, amount, volume, minAmount, minVolume, paymentMethod, offerId, offerDate, useMarketBasedPrice, marketPriceMargin}`. |
| `GET /api/v1/depth?market=BTC_EUR` | Cumulative depth per side: `{price, amount, cumulativeAmount}`. |
| `GET /api/v1/trades[?market=&from=&to=&limit=]` | Trades tape, newest first. `from`/`to` are epoch **milliseconds** (default: last 30 days), `limit` default 500. `market` optional (all markets if omitted). |
| `GET /api/v1/candles?market=BTC_EUR&interval=1h[&from=&to=]` | OHLCV candlesticks: `{time, open, high, low, close, volumeBase, volumeCounter, trades}`. `interval` e.g. `1m`,`5m`,`15m`,`1h`,`4h`,`1d`,`1w` (default `1d`). |
| `GET /api/v1/ticker[?market=]` | 24h ticker per market: `{market, last, high, low, open, buy, sell, volumeBase, volumeCounter, numTrades}` (`buy`/`sell` are best bid/ask from the live book; nulls where no data). |

---

## 4. Configuration

| Setting | Env var | System property | Default |
|---|---|---|---|
| API bind host | `BISQ_MARKETS_API_HOST` | `bisq.marketsApi.host` | `0.0.0.0` |
| API port | `BISQ_MARKETS_API_PORT` | `bisq.marketsApi.port` | `8090` |

Standard Bisq node options are passed as CLI args, notably:

- `--appDataDir=<dir>` — where the P2P data store, downloaded trade statistics and the Tor identity
  live. Persist this to skip re-bootstrapping on restart.
- `--baseCurrencyNetwork=BTC_MAINNET` (default) — or `BTC_TESTNET` / `BTC_REGTEST`.

The Docker image maps these to the `BISQ_DATA_DIR` (`/data`) and `BISQ_NETWORK` env vars.

---

## 5. Building

The build needs **JDK 21** and the **`bitcoind` git submodule** (a dependency of `core`):

```sh
# from the repository root
git submodule update --init bitcoind
./gradlew :marketsnode:installDist      # assembles build/install/marketsnode (bin/ + lib/)
```

`installDist` produces the runnable distribution the Docker image uses:
`build/install/marketsnode/bin/marketsnode` plus the classpath under `lib/`.

---

## 6. Running

### Docker (recommended — self-contained, incl. Tor)

```sh
# from the repository root (submodule must be initialised, see §5)
docker build -f marketsnode/Dockerfile -t bisq/marketsnode:latest .
docker run --rm -p 8090:8090 -v bisq-marketsnode:/data bisq/marketsnode:latest
```

### Kubernetes

See [`k8s/marketsnode.yaml`](k8s/marketsnode.yaml): Deployment + Service + PVC, with the readiness
probe wired to `/api/v1/ready` and a generous startup probe to allow for Tor bootstrap. Build and
push the image to your registry and set the `image:` reference before applying.

### Local (development)

```sh
./gradlew :marketsnode:installDist
./marketsnode/build/install/marketsnode/bin/marketsnode --appDataDir=/tmp/bisq-marketsnode
```

---

## How to test against the main network

Mainnet (`BTC_MAINNET`) is the **default**, so the steps below already exercise the real Bisq
network. The node reaches the network over Tor, which it starts itself.

### Prerequisites

- Docker, and **outbound internet access** so the bundled Tor can reach the Bisq `.onion` seed
  nodes. (No inbound ports need to be open for data collection.)
- ~2 GB RAM available to the container and a few GB of disk for the data volume.

### Step 1 — build and run

```sh
git submodule update --init bitcoind          # required for the build
docker build -f marketsnode/Dockerfile -t bisq/marketsnode:latest .
docker run --rm -p 8090:8090 -v bisq-marketsnode:/data bisq/marketsnode:latest
```

### Step 2 — wait for bootstrap

First start takes **several minutes**: the node launches Tor, connects to seed nodes, and downloads
the full offer set and trade statistics. Watch the logs for Tor coming up and P2P data being
received, then poll readiness:

```sh
# 503 while syncing, 200 once bootstrapped
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8090/api/v1/ready

# or watch the status counters climb
curl -s http://localhost:8090/api/v1/status | jq
# → {"bootstrapped": true, "numOffers": 120, "numTradeStatistics": 480000, "numMarkets": 40, ...}
```

### Step 3 — query real market data

```sh
# markets that currently have offers or trades
curl -s http://localhost:8090/api/v1/markets | jq

# live order book for BTC/EUR (bids highest-first, asks lowest-first)
curl -s 'http://localhost:8090/api/v1/orderbook?market=BTC_EUR' | jq

# cumulative depth
curl -s 'http://localhost:8090/api/v1/depth?market=BTC_EUR' | jq

# recent trades (tape), newest first
curl -s 'http://localhost:8090/api/v1/trades?market=BTC_EUR&limit=20' | jq

# daily candles
curl -s 'http://localhost:8090/api/v1/candles?market=BTC_EUR&interval=1d' | jq

# 24h ticker across all markets
curl -s http://localhost:8090/api/v1/ticker | jq
```

### Step 4 — sanity-check the values

Because this reads the same P2P data the Bisq desktop client and the public markets site use, you
can cross-check figures (last price, 24h volume, open offers) against the public
`markets.bisq.network` for the same market. Expect small differences: offers are a moment-in-time
snapshot and trade figures are rounded/seller-reported (see [§1](#data-fidelity--read-this-before-trusting-the-numbers)).

### Testnet / regtest instead

To point at a non-mainnet network, pass `--baseCurrencyNetwork` (Docker: set `BISQ_NETWORK`), e.g.
`-e BISQ_NETWORK=BTC_TESTNET`. Testnet also uses Tor; regtest is for local dev only.

### Troubleshooting

- **Stuck "not ready" / slow bootstrap.** Tor bootstrap plus the initial data sync can take 5–10
  minutes on first run, and longer on constrained networks. Persist `/data` so restarts reuse the
  Tor identity and data store.
- **`OutOfMemoryError` / OOMKilled.** Increase the container memory and set the JVM heap, e.g.
  `-e JAVA_TOOL_OPTIONS=-Xmx1536m` (the k8s manifest already sets this).
- **Empty results right after start.** You queried before `"bootstrapped": true` — wait for
  `/api/v1/ready` to return 200. A specific market may legitimately have no open offers at the
  moment; trades should still be present.
- **Networks that block Tor.** The node needs to reach Tor; on restrictive networks you'd need Tor
  bridges (Bisq supports configuring these), otherwise it cannot bootstrap.

---

## 7. Validation status

- ✅ **Compiles** cleanly against the real `core`, `p2p` and `common` modules
  (`./gradlew :marketsnode:compileJava`).
- ✅ **Distribution assembles** — `installDist` produces `bin/marketsnode` and the full runtime
  classpath, which is what the Docker image runs.
- ⚠️ **Live mainnet run and `docker build` were not executed in the development sandbox** (its
  proxy does not carry Tor's onion connections and cannot build images). The steps in
  [How to test against the main network](#how-to-test-against-the-main-network) are the intended
  smoke test to run in a real environment.

## 8. Possible follow-ups

- A live push stream (WebSocket or SSE) for order-book and trade updates (currently REST/poll only).
- Optionally strip the DAO subsystem for a smaller footprint.
- A Tor sidecar variant of the container instead of embedded Tor, if preferred for k8s.
