# marketsnode

A headless Bisq node that connects to the P2P network and exposes the **current order book** and
**trade statistics** of **all markets** as a read-only JSON HTTP API.

It reuses the same P2P-only application setup as [`statsnode`](../statsnode) (`ExecutableForAppWithP2p`):
**no Bitcoin wallet, no GUI**. All data is derived from two P2P-replicated sources:

- open offers (`OfferBookService`) — a live snapshot; offers expire ~9 minutes after a maker goes
  offline, so the order book is inherently "current state" only;
- trade statistics (`TradeStatisticsManager`, `TradeStatistics3`) — an append-only feed the node
  downloads in full on bootstrap and then receives live.

> Trade figures are **seller-reported and privacy-reduced (rounded)**, carry **no counterparty
> identity**, and completeness is best-effort. Treat the trades/candles/ticker data as market
> analytics, not an authoritative settlement ledger. See
> [`docs/feasibility-orderbook-trades-api.md`](../docs/feasibility-orderbook-trades-api.md).

## Running

### Docker (recommended)

```sh
# from the repository root
docker build -f marketsnode/Dockerfile -t bisq/marketsnode:latest .
docker run -p 8090:8090 -v bisq-marketsnode:/data bisq/marketsnode:latest
```

The image starts its own bundled Tor instance and connects autonomously — no external Tor is
required. Bootstrapping over Tor takes a few minutes on first start; poll `GET /api/v1/status`
until `"bootstrapped": true` (or use `GET /api/v1/ready`, which returns 503 until then).

### Kubernetes

See [`k8s/marketsnode.yaml`](k8s/marketsnode.yaml) (Deployment + Service + PVC, with startup /
liveness / readiness probes wired to the endpoints below).

### Gradle (local, for development)

```sh
./gradlew :marketsnode:installDist
./marketsnode/build/install/marketsnode/bin/marketsnode --appDataDir=/tmp/bisq-marketsnode
```

## Configuration

| Setting | Env var | System property | Default |
|---|---|---|---|
| API bind host | `BISQ_MARKETS_API_HOST` | `bisq.marketsApi.host` | `0.0.0.0` |
| API port | `BISQ_MARKETS_API_PORT` | `bisq.marketsApi.port` | `8090` |

Bisq node options are passed as CLI args, e.g. `--appDataDir`, `--baseCurrencyNetwork=BTC_MAINNET`.

## HTTP API

Base path `/api/v1`. All endpoints are `GET` and return JSON.

Monetary values are decimal numbers in the market's natural units: `price` and `volume` are in the
counter currency (EUR for `BTC_EUR`, BTC for `XMR_BTC`), `amount` is in the base currency (BTC for
`BTC_EUR`, XMR for `XMR_BTC`). Market keys are `BASE_COUNTER`, e.g. `BTC_EUR`, `XMR_BTC` (the
`market` parameter also accepts `BTC/EUR` and is case-insensitive).

| Endpoint | Description |
|---|---|
| `GET /api/v1/status` | Node status: `bootstrapped`, counts, version. Root `/` returns the same (liveness). |
| `GET /api/v1/ready` | 200 when bootstrapped, 503 while syncing (readiness). |
| `GET /api/v1/currencies` | Supported fiat and crypto currencies. |
| `GET /api/v1/markets` | Active markets (pairs with offers or trades). |
| `GET /api/v1/offers[?market=]` | Raw open offers (optionally filtered by market). |
| `GET /api/v1/orderbook?market=BTC_EUR` | Order book (`buys` best-price-first, `sells` best-price-first). |
| `GET /api/v1/depth?market=BTC_EUR` | Cumulative depth per side. |
| `GET /api/v1/trades[?market=&from=&to=&limit=]` | Trades tape, newest first. `from`/`to` are epoch ms; defaults to the last 30 days, `limit` 500. |
| `GET /api/v1/candles?market=BTC_EUR&interval=1h[&from=&to=]` | OHLCV candlesticks. `interval` e.g. `1m`,`15m`,`1h`,`4h`,`1d`,`1w` (default `1d`). |
| `GET /api/v1/ticker[?market=]` | 24h ticker per market: `last`,`high`,`low`,`open`,`buy`,`sell`,`volumeBase`,`volumeCounter`,`numTrades`. |
