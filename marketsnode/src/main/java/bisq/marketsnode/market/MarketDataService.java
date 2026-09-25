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

package bisq.marketsnode.market;

import bisq.marketsnode.dto.MarketDtos.CandleDto;
import bisq.marketsnode.dto.MarketDtos.CurrencyDto;
import bisq.marketsnode.dto.MarketDtos.DepthDto;
import bisq.marketsnode.dto.MarketDtos.DepthEntryDto;
import bisq.marketsnode.dto.MarketDtos.MarketDto;
import bisq.marketsnode.dto.MarketDtos.OrderBookDto;
import bisq.marketsnode.dto.MarketDtos.OrderBookEntryDto;
import bisq.marketsnode.dto.MarketDtos.StatusDto;
import bisq.marketsnode.dto.MarketDtos.SummaryDto;
import bisq.marketsnode.dto.MarketDtos.TickerDto;
import bisq.marketsnode.dto.MarketDtos.TradeDto;

import bisq.core.locale.CryptoCurrency;
import bisq.core.locale.CurrencyUtil;
import bisq.core.locale.Res;
import bisq.core.locale.TradeCurrency;
import bisq.core.monetary.Volume;
import bisq.core.offer.OfferBookService;
import bisq.core.offer.OfferDirection;
import bisq.core.offer.OfferForJson;
import bisq.core.trade.statistics.TradeStatistics3;
import bisq.core.trade.statistics.TradeStatisticsManager;

import bisq.network.p2p.P2PService;

import bisq.common.app.Version;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

/**
 * Derives order books, depth, a trades tape, candlesticks and per-market tickers from the live
 * P2P data: open offers ({@link OfferBookService}) and trade statistics
 * ({@link TradeStatisticsManager}). Nothing here mutates network state; it only reads snapshots.
 *
 * <p>Monetary scaling: Bisq stores prices/volumes as scaled integers (4 decimals for fiat, 8 for
 * altcoins/BTC). We normalise everything to decimal numbers in the market's natural units — see
 * {@link bisq.marketsnode.dto.MarketDtos} for the field semantics.
 */
@Slf4j
public class MarketDataService {
    private static final double SATOSHI = 1e8;
    private static final double FIAT = 1e4;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final OfferBookService offerBookService;
    private final TradeStatisticsManager tradeStatisticsManager;
    private final P2PService p2pService;

    public MarketDataService(OfferBookService offerBookService,
                             TradeStatisticsManager tradeStatisticsManager,
                             P2PService p2pService) {
        this.offerBookService = offerBookService;
        this.tradeStatisticsManager = tradeStatisticsManager;
        this.p2pService = p2pService;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Status / metadata
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean isBootstrapped() {
        return p2pService.isBootstrapped();
    }

    public StatusDto status() {
        List<OfferForJson> offers = offers();
        List<TradeStatistics3> trades = trades();
        int numMarkets = (int) java.util.stream.Stream.concat(
                        offers.stream().map(this::marketOf),
                        trades.stream().map(this::marketOf))
                .filter(s -> !s.isBlank())
                .distinct()
                .count();
        return new StatusDto(p2pService.isBootstrapped(), offers.size(), trades.size(), numMarkets,
                Version.VERSION, System.currentTimeMillis());
    }

    public List<CurrencyDto> currencies() {
        List<CurrencyDto> result = new ArrayList<>();
        for (TradeCurrency e : CurrencyUtil.getMatureMarketCurrencies()) {
            result.add(new CurrencyDto(e.getCode(), e.getName(), "fiat"));
        }
        for (CryptoCurrency e : CurrencyUtil.getMainCryptoCurrencies()) {
            result.add(new CurrencyDto(e.getCode(), e.getName(), "crypto"));
        }
        return result;
    }

    public List<MarketDto> markets() {
        Map<String, MarketDto> byPair = new TreeMap<>();
        offers().stream().map(this::marketOf).filter(s -> !s.isBlank())
                .forEach(pair -> byPair.computeIfAbsent(pair, this::toMarketDto));
        trades().stream().map(this::marketOf).filter(s -> !s.isBlank())
                .forEach(pair -> byPair.computeIfAbsent(pair, this::toMarketDto));
        return new ArrayList<>(byPair.values());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Order book / depth
    ///////////////////////////////////////////////////////////////////////////////////////////

    public OrderBookDto orderBook(String market) {
        String pair = normalizeMarket(market);
        List<OrderBookEntryDto> buys = new ArrayList<>();
        List<OrderBookEntryDto> sells = new ArrayList<>();
        for (OfferForJson offer : offers()) {
            if (!marketOf(offer).equals(pair)) {
                continue;
            }
            OrderBookEntryDto entry = toOrderBookEntry(offer);
            if (offer.primaryMarketDirection == OfferDirection.BUY) {
                buys.add(entry);
            } else {
                sells.add(entry);
            }
        }
        // Best price first: buys (bids) highest first, sells (asks) lowest first.
        buys.sort(Comparator.comparingDouble((OrderBookEntryDto e) -> e.price).reversed());
        sells.sort(Comparator.comparingDouble(e -> e.price));
        String[] parts = splitPair(pair);
        return new OrderBookDto(pair, parts[0], parts[1], buys, sells);
    }

    public DepthDto depth(String market) {
        OrderBookDto book = orderBook(market);
        return new DepthDto(book.market, cumulate(book.buys), cumulate(book.sells));
    }

    private static List<DepthEntryDto> cumulate(List<OrderBookEntryDto> entries) {
        List<DepthEntryDto> result = new ArrayList<>();
        double cumulative = 0;
        for (OrderBookEntryDto e : entries) {
            cumulative += e.amount;
            result.add(new DepthEntryDto(e.price, e.amount, cumulative));
        }
        return result;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Trades / candles / ticker
    ///////////////////////////////////////////////////////////////////////////////////////////

    public List<TradeDto> trades(String market, long from, long to, int limit) {
        String pair = market == null ? null : normalizeMarket(market);
        List<TradeDto> result = trades().stream()
                .filter(t -> pair == null || marketOf(t).equals(pair))
                .filter(t -> t.getDateAsLong() >= from && t.getDateAsLong() <= to)
                .sorted(Comparator.comparingLong(TradeStatistics3::getDateAsLong).reversed())
                .map(this::toTradeDto)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        if (limit > 0 && result.size() > limit) {
            return new ArrayList<>(result.subList(0, limit));
        }
        return result;
    }

    public List<CandleDto> candles(String market, String interval, long from, long to) {
        String pair = normalizeMarket(market);
        long intervalMs = intervalToMillis(interval);
        // Keyed by bucket start, ascending in time.
        Map<Long, CandleAccumulator> buckets = new TreeMap<>();
        trades().stream()
                .filter(t -> marketOf(t).equals(pair))
                .filter(t -> t.getDateAsLong() >= from && t.getDateAsLong() <= to)
                .sorted(Comparator.comparingLong(TradeStatistics3::getDateAsLong))
                .forEach(t -> {
                    TradeDto dto = toTradeDto(t);
                    if (dto == null) {
                        return;
                    }
                    long bucket = (t.getDateAsLong() / intervalMs) * intervalMs;
                    buckets.computeIfAbsent(bucket, CandleAccumulator::new).add(dto);
                });
        return buckets.values().stream().map(CandleAccumulator::toDto).collect(Collectors.toList());
    }

    public List<TickerDto> ticker(String market) {
        String pair = market == null ? null : normalizeMarket(market);
        long now = System.currentTimeMillis();
        long dayAgo = now - DAY_MS;

        // Group the last 24h of trades per market.
        Map<String, List<TradeDto>> tradesByMarket = new LinkedHashMap<>();
        trades().stream()
                .filter(t -> t.getDateAsLong() >= dayAgo)
                .sorted(Comparator.comparingLong(TradeStatistics3::getDateAsLong))
                .forEach(t -> {
                    String m = marketOf(t);
                    if (pair != null && !m.equals(pair)) {
                        return;
                    }
                    TradeDto dto = toTradeDto(t);
                    if (dto != null) {
                        tradesByMarket.computeIfAbsent(m, k -> new ArrayList<>()).add(dto);
                    }
                });

        // When a specific market was requested we still return a (possibly empty) ticker for it.
        if (pair != null) {
            tradesByMarket.computeIfAbsent(pair, k -> new ArrayList<>());
        }

        List<TickerDto> result = new ArrayList<>();
        for (Map.Entry<String, List<TradeDto>> e : tradesByMarket.entrySet()) {
            result.add(toTicker(e.getKey(), e.getValue()));
        }
        return result;
    }

    private TickerDto toTicker(String pair, List<TradeDto> dayTrades) {
        Double last = null;
        Double high = null;
        Double low = null;
        Double open = null;
        double volumeBase = 0;
        double volumeCounter = 0;
        // dayTrades is ascending in time.
        for (TradeDto t : dayTrades) {
            if (open == null) {
                open = t.price;
            }
            last = t.price;
            high = high == null ? t.price : Math.max(high, t.price);
            low = low == null ? t.price : Math.min(low, t.price);
            volumeBase += t.amount;
            volumeCounter += t.volume;
        }
        OrderBookDto book = orderBook(pair);
        Double buy = book.buys.isEmpty() ? null : book.buys.get(0).price;
        Double sell = book.sells.isEmpty() ? null : book.sells.get(0).price;
        return new TickerDto(pair, last, high, low, open, buy, sell, volumeBase, volumeCounter, dayTrades.size());
    }


    /**
     * Ticker + book liquidity of every market in one pass (see {@link SummaryDto}): markets with offers
     * or trade statistics. Makers are distinct offer node addresses.
     */
    public List<SummaryDto> summary() {
        Map<String, TickerDto> tickers = new TreeMap<>();
        // ticker(null) builds each market's book too; only the 24h trade fields are taken from it
        for (TickerDto t : ticker(null)) {
            tickers.put(t.market, t);
        }
        Map<String, String> makerByOfferId = new java.util.HashMap<>();
        withRetry(offerBookService::getOffers).forEach(o -> {
            if (o.getMakerNodeAddress() != null) {
                makerByOfferId.put(o.getId(), o.getMakerNodeAddress().getFullAddress());
            }
        });
        Map<String, List<OfferForJson>> offersByMarket = new TreeMap<>();
        for (OfferForJson o : offers()) {
            String m = marketOf(o);
            if (!m.isBlank()) {
                offersByMarket.computeIfAbsent(m, k -> new ArrayList<>()).add(o);
            }
        }
        java.util.Set<String> pairs = new java.util.TreeSet<>(offersByMarket.keySet());
        pairs.addAll(tickers.keySet());

        List<SummaryDto> result = new ArrayList<>();
        for (String pair : pairs) {
            List<OrderBookEntryDto> bids = new ArrayList<>();
            List<OrderBookEntryDto> asks = new ArrayList<>();
            java.util.Set<String> makers = new java.util.HashSet<>();
            for (OfferForJson o : offersByMarket.getOrDefault(pair, List.of())) {
                (o.primaryMarketDirection == OfferDirection.BUY ? bids : asks).add(toOrderBookEntry(o));
                String maker = makerByOfferId.get(o.id);
                if (maker != null) {
                    makers.add(maker);
                }
            }
            TickerDto t = tickers.get(pair);
            String[] parts = splitPair(pair);
            result.add(new SummaryDto(pair, parts[0], parts[1],
                    t == null ? null : t.last, t == null ? null : t.open,
                    t == null ? null : t.high, t == null ? null : t.low,
                    t == null ? 0 : t.volumeBase, t == null ? 0 : t.volumeCounter, t == null ? 0 : t.numTrades,
                    bids.stream().mapToDouble(e -> e.price).max().stream().boxed().findFirst().orElse(null),
                    asks.stream().mapToDouble(e -> e.price).min().stream().boxed().findFirst().orElse(null),
                    bids.size(), asks.size(), sumAmount(bids), sumAmount(asks), vwap(bids), vwap(asks),
                    makers.size()));
        }
        return result;
    }

    private static double sumAmount(List<OrderBookEntryDto> entries) {
        return entries.stream().mapToDouble(e -> e.amount).sum();
    }

    /** Size-weighted average price of a book side; null when the side is empty. */
    private static Double vwap(List<OrderBookEntryDto> entries) {
        double size = sumAmount(entries);
        if (size <= 0) {
            return null;
        }
        return entries.stream().mapToDouble(e -> e.price * e.amount).sum() / size;
    }


        ///////////////////////////////////////////////////////////////////////////////////////////
    // Offers (raw)
    ///////////////////////////////////////////////////////////////////////////////////////////

    public List<OfferForJson> offersForMarket(String market) {
        if (market == null) {
            return offers();
        }
        String pair = normalizeMarket(market);
        return offers().stream().filter(o -> marketOf(o).equals(pair)).collect(Collectors.toList());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Conversions
    ///////////////////////////////////////////////////////////////////////////////////////////

    private OrderBookEntryDto toOrderBookEntry(OfferForJson o) {
        return new OrderBookEntryDto(
                o.primaryMarketPrice / SATOSHI,
                o.primaryMarketAmount / SATOSHI,
                o.primaryMarketVolume / SATOSHI,
                o.primaryMarketMinAmount / SATOSHI,
                o.primaryMarketMinVolume / SATOSHI,
                o.paymentMethod,
                o.id,
                o.date,
                o.useMarketBasedPrice,
                o.marketPriceMargin);
    }

    private TradeDto toTradeDto(TradeStatistics3 t) {
        try {
            String currency = t.getCurrency();
            boolean crypto = CurrencyUtil.isCryptoCurrency(currency);
            String pair = marketOf(t);
            Volume volume = t.getTradeVolume();
            double price;
            double amount;
            double counterVolume;
            if (crypto) {
                if (volume == null) {
                    return null;
                }
                price = t.getTradePrice().getValue() / SATOSHI; // BTC per altcoin
                amount = volume.getValue() / SATOSHI;           // altcoin amount
                counterVolume = t.getAmount() / SATOSHI;        // BTC
            } else {
                price = t.getTradePrice().getValue() / FIAT;    // fiat per BTC
                amount = t.getAmount() / SATOSHI;               // BTC
                counterVolume = volume != null ? volume.getValue() / FIAT : price * amount;
            }
            return new TradeDto(pair, price, amount, counterVolume, t.getPaymentMethodId(), t.getDateAsLong());
        } catch (Throwable throwable) {
            log.warn("Could not convert trade statistic {}: {}", t, throwable.toString());
            return null;
        }
    }

    private MarketDto toMarketDto(String pair) {
        String[] parts = splitPair(pair);
        return new MarketDto(pair, parts[0], parts[1], parts[0] + "/" + parts[1]);
    }

    private String marketOf(OfferForJson o) {
        // currencyPair can be null if the offer could not be fully parsed (setDisplayStrings failed).
        return o.currencyPair == null ? "" : o.currencyPair.replace("/", "_").toUpperCase();
    }

    private String marketOf(TradeStatistics3 t) {
        String currency = t.getCurrency().toUpperCase();
        String base = Res.getBaseCurrencyCode(); // "BTC" on mainnet
        return CurrencyUtil.isCryptoCurrency(t.getCurrency())
                ? currency + "_" + base
                : base + "_" + currency;
    }

    private String normalizeMarket(String market) {
        return market.trim().replace("/", "_").toUpperCase();
    }

    private static String[] splitPair(String pair) {
        int i = pair.indexOf('_');
        if (i < 0) {
            return new String[]{pair, ""};
        }
        return new String[]{pair.substring(0, i), pair.substring(i + 1)};
    }

    private static long intervalToMillis(String interval) {
        if (interval == null || interval.isBlank()) {
            return DAY_MS;
        }
        String value = interval.trim().toLowerCase();
        char unit = value.charAt(value.length() - 1);
        long count;
        try {
            count = Long.parseLong(value.substring(0, value.length() - 1));
        } catch (NumberFormatException e) {
            return DAY_MS;
        }
        if (count <= 0) {
            return DAY_MS;
        }
        switch (unit) {
            case 'm':
                return count * 60_000L;
            case 'h':
                return count * 60 * 60_000L;
            case 'd':
                return count * DAY_MS;
            case 'w':
                return count * 7 * DAY_MS;
            default:
                return DAY_MS;
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Snapshots (defensive against concurrent mutation on the P2P/user thread)
    ///////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Offers in the bisq.markets JSON form. Built here with the offer's own (BTC-side) direction,
     * like {@code OfferBookService.doDumpStatistics}: {@link OfferForJson} already mirrors the
     * direction for altcoin markets, so {@code OfferBookService.getOfferForJsonList} (which mirrors
     * it once more for its own consumers) would report every altcoin buy offer as a sell and vice versa.
     */
    private List<OfferForJson> offers() {
        return withRetry(() -> offerBookService.getOffers().stream()
                .<OfferForJson>map(offer -> {
                    try {
                        return new OfferForJson(offer.getDirection(),
                                offer.getCurrencyCode(),
                                offer.getMinAmount(),
                                offer.getAmount(),
                                offer.getPrice(),
                                offer.getDate(),
                                offer.getId(),
                                offer.isUseMarketBasedPrice(),
                                offer.getMarketPriceMargin(),
                                offer.getPaymentMethod());
                    } catch (Throwable t) {
                        // An offer with corrupted (null) values, or a market-based price without a
                        // price feed; skip it like the statistics dump does.
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList()));
    }

    private List<TradeStatistics3> trades() {
        return withRetry(() -> new ArrayList<>(tradeStatisticsManager.getObservableTradeStatisticsSet()));
    }

    /**
     * The P2P data structures are mutated on the user thread while we read them from HTTP handler
     * threads, so a rare {@link ConcurrentModificationException} is possible mid-iteration. Retry a
     * few times before giving up.
     */
    private static <T> List<T> withRetry(Supplier<List<T>> supplier) {
        ConcurrentModificationException last = null;
        for (int i = 0; i < 5; i++) {
            try {
                return supplier.get();
            } catch (ConcurrentModificationException e) {
                last = e;
            }
        }
        throw last;
    }
}
