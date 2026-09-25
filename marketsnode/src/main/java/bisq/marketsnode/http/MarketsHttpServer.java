/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.marketsnode.http;

import bisq.marketsnode.market.MarketDataService;

import bisq.core.util.JsonUtil;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

/**
 * Minimal read-only JSON HTTP API over the JDK built-in {@link HttpServer} (no external web
 * framework). All routes are GET-only and take their arguments as query parameters, e.g.
 * {@code /api/v1/orderbook?market=BTC_EUR}. Responses are JSON produced by {@link JsonUtil}.
 */
@Slf4j
public class MarketsHttpServer {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final long DEFAULT_TRADES_WINDOW = 30 * DAY_MS;
    private static final int DEFAULT_TRADES_LIMIT = 500;

    private final String host;
    private final int port;
    private final MarketDataService marketDataService;
    private HttpServer httpServer;
    private ExecutorService executor;

    public MarketsHttpServer(String host, int port, MarketDataService marketDataService) {
        this.host = host;
        this.port = port;
        this.marketDataService = marketDataService;
    }

    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        AtomicInteger threadCounter = new AtomicInteger();
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "marketsApi-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        httpServer.setExecutor(executor);

        register("/api/v1/status", params -> marketDataService.status());
        register("/api/v1/currencies", params -> marketDataService.currencies());
        register("/api/v1/markets", params -> marketDataService.markets());
        register("/api/v1/offers", params -> marketDataService.offersForMarket(params.get("market")));
        register("/api/v1/orderbook", params -> marketDataService.orderBook(requireMarket(params)));
        register("/api/v1/depth", params -> marketDataService.depth(requireMarket(params)));
        register("/api/v1/trades", params -> {
            long to = parseLong(params.get("to"), System.currentTimeMillis());
            long from = parseLong(params.get("from"), to - DEFAULT_TRADES_WINDOW);
            int limit = (int) parseLong(params.get("limit"), DEFAULT_TRADES_LIMIT);
            return marketDataService.trades(params.get("market"), from, to, limit);
        });
        register("/api/v1/candles", params -> {
            long to = parseLong(params.get("to"), System.currentTimeMillis());
            long from = parseLong(params.get("from"), to - DEFAULT_TRADES_WINDOW);
            return marketDataService.candles(requireMarket(params), params.get("interval"), from, to);
        });
        register("/api/v1/ticker", params -> marketDataService.ticker(params.get("market")));
        register("/api/v1/summary", params -> marketDataService.summary());
        // Readiness: 200 once the P2P network is bootstrapped, 503 while still syncing.
        httpServer.createContext("/api/v1/ready", new ReadinessHandler(marketDataService));
        // Root context doubles as a liveness check and reports current status.
        register("/", params -> marketDataService.status());

        httpServer.start();
        log.info("Markets HTTP API listening on http://{}:{}/api/v1", host, port);
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(1);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void register(String path, Route route) {
        httpServer.createContext(path, new JsonHandler(route));
    }

    private static String requireMarket(Map<String, String> params) {
        String market = params.get("market");
        if (market == null || market.isBlank()) {
            throw new IllegalArgumentException("Missing required query parameter 'market' (e.g. ?market=BTC_EUR)");
        }
        return market;
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private interface Route {
        Object handle(Map<String, String> params) throws Exception;
    }

    /** Dedicated handler so the HTTP status code (not just the body) reflects readiness. */
    private static class ReadinessHandler implements HttpHandler {
        private final MarketDataService marketDataService;

        ReadinessHandler(MarketDataService marketDataService) {
            this.marketDataService = marketDataService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                boolean ready = marketDataService.isBootstrapped();
                JsonHandler.writeJson(exchange, ready ? 200 : 503, "{\"ready\":" + ready + "}");
            } finally {
                exchange.close();
            }
        }
    }

    private static class JsonHandler implements HttpHandler {
        private final Route route;

        JsonHandler(Route route) {
            this.route = route;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writeJson(exchange, 405, "{\"error\":\"Only GET is supported\"}");
                    return;
                }
                Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
                Object result = route.handle(params);
                writeJson(exchange, 200, JsonUtil.objectToJson(result));
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, errorJson(e.getMessage()));
            } catch (Exception e) {
                log.warn("Error handling {}", exchange.getRequestURI(), e);
                writeJson(exchange, 500, errorJson("Internal error: " + e.getMessage()));
            } finally {
                exchange.close();
            }
        }

        private static Map<String, String> parseQuery(String rawQuery) {
            Map<String, String> params = new HashMap<>();
            if (rawQuery == null || rawQuery.isEmpty()) {
                return params;
            }
            for (String pair : rawQuery.split("&")) {
                int i = pair.indexOf('=');
                if (i < 0) {
                    params.put(decode(pair), "");
                } else {
                    params.put(decode(pair.substring(0, i)), decode(pair.substring(i + 1)));
                }
            }
            return params;
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }

        private static String errorJson(String message) {
            return "{\"error\":" + quote(message) + "}";
        }

        private static String quote(String value) {
            if (value == null) {
                return "null";
            }
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
