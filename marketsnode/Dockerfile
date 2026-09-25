# syntax=docker/dockerfile:1
#
# Self-contained image for the Bisq marketsnode: a headless P2P node that connects to the Bisq
# network (over Tor) and exposes the current order book and trade statistics of all markets as a
# read-only JSON HTTP API.
#
# Build from the repository root:
#   docker build -f marketsnode/Dockerfile -t bisq/marketsnode:latest .
#
# Run:
#   docker run -p 8090:8090 -v bisq-marketsnode:/data bisq/marketsnode:latest
#
# The container starts its own bundled Tor instance, so no external Tor is required. Bootstrapping
# over Tor takes a few minutes on first start; poll GET /api/v1/status until "bootstrapped": true.

########################  build stage  ########################
FROM eclipse-temurin:21-jdk AS build

WORKDIR /src
# Copy the whole repository so the Gradle multi-project build can resolve every module.
COPY . .
# installDist produces build/install/marketsnode with a lib/ dir and a launch script.
RUN --mount=type=cache,id=bisq-gradle,target=/root/.gradle \
    ./gradlew --no-daemon :marketsnode:installDist

########################  runtime stage  ########################
FROM eclipse-temurin:21-jre

# A glibc-based JRE image is required: Bisq starts a bundled Tor binary (via the netlayer library)
# which is extracted into the data dir at runtime and needs a normal Linux userland.
RUN useradd --create-home --uid 10001 bisq
WORKDIR /app
COPY --from=build /src/marketsnode/build/install/marketsnode /app

# Data dir holds the P2P data store, downloaded trade statistics and the Tor identity/state.
ENV BISQ_DATA_DIR=/data \
    BISQ_MARKETS_API_HOST=0.0.0.0 \
    BISQ_MARKETS_API_PORT=8090 \
    BISQ_NETWORK=BTC_MAINNET
RUN mkdir -p /data && chown -R bisq:bisq /data /app
USER bisq
VOLUME ["/data"]
EXPOSE 8090

# The installDist launch script is build/install/marketsnode/bin/marketsnode.
# --appDataDir keeps all state on the mounted volume so restarts skip re-bootstrapping.
# Extra container args are appended, e.g. to use an already running (shared) Tor instead of the
# bundled one: --torControlHost=tor.tor.svc --torControlPort=9051 --torControlPassword=...
ENTRYPOINT ["/bin/sh", "-c", "exec /app/bin/marketsnode --appDataDir=\"$BISQ_DATA_DIR\" --baseCurrencyNetwork=\"$BISQ_NETWORK\" \"$@\"", "--"]
