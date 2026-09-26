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

package bisq.marketsnode.dto;

import java.util.List;

/**
 * Plain data holders for the JSON responses of the markets API.
 * <p>
 * All monetary values are expressed as decimal numbers in the natural units of the market:
 * {@code price} and {@code volume} are in the counter currency (e.g. EUR for the BTC_EUR market,
 * BTC for the XMR_BTC market), and {@code amount} is in the base currency (e.g. BTC for BTC_EUR,
 * XMR for XMR_BTC). Trade figures are seller-reported and privacy-reduced (rounded), so treat them
 * as market analytics rather than an authoritative ledger.
 */
public final class MarketDtos {
    private MarketDtos() {
    }

    public static final class StatusDto {
        public final boolean bootstrapped;
        public final int numOffers;
        public final int numTradeStatistics;
        public final int numMarkets;
        /** Our Bisq core version ({@code Version.VERSION}). */
        public final String version;
        public final long timestamp;
        /** Connected P2P peers right now. */
        public final int numConnectedPeers;
        /** The network's signed filter demands a newer version for trading / the DAO (Bisq's
         *  "mandatory update" popups): the node then refuses to publish or take offers. */
        public final boolean requireUpdateForTrading;
        public final boolean requireUpdateForDao;
        /** The filter's version floors as published (null when unset). */
        public final String disableTradeBelowVersion;
        public final String disableDaoBelowVersion;
        /** The latest developer alert on the network, or null. */
        public final AlertDto alert;
        /** The highest Bisq version among the open offers' makers, and how many offers come from a
         *  version newer than ours — the network moving on shows here before any filter does. */
        public final String maxOfferVersion;
        public final int offersNewerThanOurs;

        public StatusDto(boolean bootstrapped, int numOffers, int numTradeStatistics, int numMarkets,
                         String version, long timestamp, int numConnectedPeers,
                         boolean requireUpdateForTrading, boolean requireUpdateForDao,
                         String disableTradeBelowVersion, String disableDaoBelowVersion,
                         AlertDto alert, String maxOfferVersion, int offersNewerThanOurs) {
            this.bootstrapped = bootstrapped;
            this.numOffers = numOffers;
            this.numTradeStatistics = numTradeStatistics;
            this.numMarkets = numMarkets;
            this.version = version;
            this.timestamp = timestamp;
            this.numConnectedPeers = numConnectedPeers;
            this.requireUpdateForTrading = requireUpdateForTrading;
            this.requireUpdateForDao = requireUpdateForDao;
            this.disableTradeBelowVersion = disableTradeBelowVersion;
            this.disableDaoBelowVersion = disableDaoBelowVersion;
            this.alert = alert;
            this.maxOfferVersion = maxOfferVersion;
            this.offersNewerThanOurs = offersNewerThanOurs;
        }
    }

    /** A developer alert ({@code bisq.core.alert.Alert}); {@code updateInfo} marks a release
     *  announcement, {@code newerThanOurs} whether its version is newer than the running one. */
    public static final class AlertDto {
        public final String message;
        public final String version;
        public final boolean updateInfo;
        public final boolean preReleaseInfo;
        public final boolean newerThanOurs;

        public AlertDto(String message, String version, boolean updateInfo, boolean preReleaseInfo,
                        boolean newerThanOurs) {
            this.message = message;
            this.version = version;
            this.updateInfo = updateInfo;
            this.preReleaseInfo = preReleaseInfo;
            this.newerThanOurs = newerThanOurs;
        }
    }

    public static final class CurrencyDto {
        public final String code;
        public final String name;
        public final String type;

        public CurrencyDto(String code, String name, String type) {
            this.code = code;
            this.name = name;
            this.type = type;
        }
    }

    public static final class MarketDto {
        public final String pair;
        public final String base;
        public final String counter;
        public final String name;

        public MarketDto(String pair, String base, String counter, String name) {
            this.pair = pair;
            this.base = base;
            this.counter = counter;
            this.name = name;
        }
    }

    public static final class OrderBookEntryDto {
        public final double price;
        public final double amount;
        public final double volume;
        public final double minAmount;
        public final double minVolume;
        public final String paymentMethod;
        public final String offerId;
        public final long offerDate;
        public final boolean useMarketBasedPrice;
        public final double marketPriceMargin;

        public OrderBookEntryDto(double price, double amount, double volume, double minAmount, double minVolume,
                                 String paymentMethod, String offerId, long offerDate,
                                 boolean useMarketBasedPrice, double marketPriceMargin) {
            this.price = price;
            this.amount = amount;
            this.volume = volume;
            this.minAmount = minAmount;
            this.minVolume = minVolume;
            this.paymentMethod = paymentMethod;
            this.offerId = offerId;
            this.offerDate = offerDate;
            this.useMarketBasedPrice = useMarketBasedPrice;
            this.marketPriceMargin = marketPriceMargin;
        }
    }

    public static final class OrderBookDto {
        public final String market;
        public final String base;
        public final String counter;
        public final List<OrderBookEntryDto> buys;
        public final List<OrderBookEntryDto> sells;

        public OrderBookDto(String market, String base, String counter,
                            List<OrderBookEntryDto> buys, List<OrderBookEntryDto> sells) {
            this.market = market;
            this.base = base;
            this.counter = counter;
            this.buys = buys;
            this.sells = sells;
        }
    }

    public static final class DepthEntryDto {
        public final double price;
        public final double amount;
        public final double cumulativeAmount;

        public DepthEntryDto(double price, double amount, double cumulativeAmount) {
            this.price = price;
            this.amount = amount;
            this.cumulativeAmount = cumulativeAmount;
        }
    }

    public static final class DepthDto {
        public final String market;
        public final List<DepthEntryDto> buys;
        public final List<DepthEntryDto> sells;

        public DepthDto(String market, List<DepthEntryDto> buys, List<DepthEntryDto> sells) {
            this.market = market;
            this.buys = buys;
            this.sells = sells;
        }
    }

    public static final class TradeDto {
        public final String market;
        public final double price;
        public final double amount;
        public final double volume;
        public final String paymentMethod;
        public final long date;

        public TradeDto(String market, double price, double amount, double volume, String paymentMethod, long date) {
            this.market = market;
            this.price = price;
            this.amount = amount;
            this.volume = volume;
            this.paymentMethod = paymentMethod;
            this.date = date;
        }
    }

    public static final class CandleDto {
        public final long time;
        public final double open;
        public final double high;
        public final double low;
        public final double close;
        public final double volumeBase;
        public final double volumeCounter;
        public final int trades;

        public CandleDto(long time, double open, double high, double low, double close,
                         double volumeBase, double volumeCounter, int trades) {
            this.time = time;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volumeBase = volumeBase;
            this.volumeCounter = volumeCounter;
            this.trades = trades;
        }
    }

    public static final class TickerDto {
        public final String market;
        public final Double last;
        public final Double high;
        public final Double low;
        public final Double open;
        public final Double buy;
        public final Double sell;
        public final double volumeBase;
        public final double volumeCounter;
        public final int numTrades;

        public TickerDto(String market, Double last, Double high, Double low, Double open,
                         Double buy, Double sell, double volumeBase, double volumeCounter, int numTrades) {
            this.market = market;
            this.last = last;
            this.high = high;
            this.low = low;
            this.open = open;
            this.buy = buy;
            this.sell = sell;
            this.volumeBase = volumeBase;
            this.volumeCounter = volumeCounter;
            this.numTrades = numTrades;
        }
    }

    /**
     * One market's ticker + order-book liquidity in a single row, for whole-universe consumers (a market
     * screener) that would otherwise need one order-book call per market. Book fields describe the
     * resting offers: counts, total size in the base asset and the size-weighted average price of each
     * side; {@code makerCount} is the number of distinct maker nodes with an offer in the market.
     */
    public static final class SummaryDto {
        public final String market;
        public final String base;
        public final String counter;
        public final Double last;
        public final Double open;
        public final Double high;
        public final Double low;
        public final double volumeBase;
        public final double volumeCounter;
        public final int numTrades;
        public final Double bestBid;
        public final Double bestAsk;
        public final int bidOfferCount;
        public final int askOfferCount;
        public final double bidVolume;
        public final double askVolume;
        public final Double bidVwap;
        public final Double askVwap;
        public final int makerCount;

        public SummaryDto(String market, String base, String counter, Double last, Double open, Double high,
                          Double low, double volumeBase, double volumeCounter, int numTrades, Double bestBid,
                          Double bestAsk, int bidOfferCount, int askOfferCount, double bidVolume,
                          double askVolume, Double bidVwap, Double askVwap, int makerCount) {
            this.market = market;
            this.base = base;
            this.counter = counter;
            this.last = last;
            this.open = open;
            this.high = high;
            this.low = low;
            this.volumeBase = volumeBase;
            this.volumeCounter = volumeCounter;
            this.numTrades = numTrades;
            this.bestBid = bestBid;
            this.bestAsk = bestAsk;
            this.bidOfferCount = bidOfferCount;
            this.askOfferCount = askOfferCount;
            this.bidVolume = bidVolume;
            this.askVolume = askVolume;
            this.bidVwap = bidVwap;
            this.askVwap = askVwap;
            this.makerCount = makerCount;
        }
    }
}
