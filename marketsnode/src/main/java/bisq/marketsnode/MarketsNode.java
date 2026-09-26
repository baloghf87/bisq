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

package bisq.marketsnode;

import bisq.marketsnode.http.MarketsHttpServer;
import bisq.marketsnode.market.MarketDataService;

import bisq.core.alert.AlertManager;
import bisq.core.filter.FilterManager;
import bisq.core.offer.OfferBookService;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.trade.statistics.TradeStatisticsManager;

import bisq.network.p2p.BootstrapListener;
import bisq.network.p2p.P2PService;

import com.google.inject.Injector;

import java.io.IOException;

import lombok.extern.slf4j.Slf4j;

/**
 * Wires the P2P data sources ({@link OfferBookService}, {@link TradeStatisticsManager}) to the
 * read-only HTTP API. The HTTP server is started immediately so that liveness checks succeed while
 * the node is still bootstrapping; the {@code /status} endpoint reports whether the P2P network is
 * bootstrapped yet. {@code TradeStatisticsManager.onAllServicesInitialized()} is already invoked by
 * the shared {@code AppSetupWithP2P} startup sequence, so we do not call it here.
 */
@Slf4j
public class MarketsNode {
    private static final String DEFAULT_HOST = "0.0.0.0";
    private static final int DEFAULT_PORT = 8090;

    private final P2PService p2pService;
    private final PriceFeedService priceFeedService;
    // Pinned so the services are not garbage collected; also read by the aggregation layer.
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private final TradeStatisticsManager tradeStatisticsManager;
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private final OfferBookService offerBookService;
    private final AlertManager alertManager;
    private final MarketsHttpServer httpServer;

    public MarketsNode(Injector injector) {
        p2pService = injector.getInstance(P2PService.class);
        priceFeedService = injector.getInstance(PriceFeedService.class);
        tradeStatisticsManager = injector.getInstance(TradeStatisticsManager.class);
        offerBookService = injector.getInstance(OfferBookService.class);
        // The filter is initialised by AppSetupWithP2P; the alert manager is bound but nothing else
        // creates it on a headless node. Created here (before startup) it listens for alerts arriving
        // from the network; alerts already in the persisted data map are replayed on data received.
        FilterManager filterManager = injector.getInstance(FilterManager.class);
        alertManager = injector.getInstance(AlertManager.class);

        MarketDataService marketDataService = new MarketDataService(offerBookService,
                tradeStatisticsManager,
                p2pService,
                filterManager,
                alertManager);
        httpServer = new MarketsHttpServer(resolveHost(), resolvePort(), marketDataService);
    }

    public void startApplication() {
        // The price feed is needed to resolve the effective price of market-based-price offers.
        priceFeedService.setCurrencyCode("USD");
        p2pService.addP2PServiceListener(new BootstrapListener() {
            @Override
            public void onDataReceived() {
                alertManager.onAllServicesInitialized();
                log.info("P2P data received; requesting price feed for market-based offers");
                priceFeedService.requestPriceFeed(
                        price -> log.info("requestPriceFeed succeeded, price={}", price),
                        (errorMessage, throwable) -> log.warn("requestPriceFeed failed: {}", errorMessage));
            }
        });

        try {
            httpServer.start();
        } catch (IOException e) {
            // A node that cannot expose its API has no purpose, so fail fast.
            throw new IllegalStateException("Could not start the markets HTTP server", e);
        }
    }

    public void shutDown() {
        httpServer.stop();
    }

    private static String resolveHost() {
        String host = System.getenv("BISQ_MARKETS_API_HOST");
        if (host == null || host.isBlank()) {
            host = System.getProperty("bisq.marketsApi.host", DEFAULT_HOST);
        }
        return host;
    }

    private static int resolvePort() {
        String value = System.getenv("BISQ_MARKETS_API_PORT");
        if (value == null || value.isBlank()) {
            value = System.getProperty("bisq.marketsApi.port");
        }
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid markets API port '{}', falling back to {}", value, DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }
}
