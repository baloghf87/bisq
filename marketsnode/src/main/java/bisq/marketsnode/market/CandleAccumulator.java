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
import bisq.marketsnode.dto.MarketDtos.TradeDto;

/**
 * Accumulates the trades that fall into one candlestick interval. Trades must be added in
 * ascending time order so that {@code open} and {@code close} are the first and last prices.
 */
class CandleAccumulator {
    private final long time;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volumeBase;
    private double volumeCounter;
    private int trades;

    CandleAccumulator(long bucketStart) {
        this.time = bucketStart;
    }

    void add(TradeDto trade) {
        if (trades == 0) {
            open = trade.price;
            high = trade.price;
            low = trade.price;
        } else {
            high = Math.max(high, trade.price);
            low = Math.min(low, trade.price);
        }
        close = trade.price;
        volumeBase += trade.amount;
        volumeCounter += trade.volume;
        trades++;
    }

    CandleDto toDto() {
        return new CandleDto(time, open, high, low, close, volumeBase, volumeCounter, trades);
    }
}
