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

import bisq.core.app.misc.ExecutableForAppWithP2p;

import bisq.common.app.Version;

import lombok.extern.slf4j.Slf4j;

/**
 * Headless Bisq node that connects to the P2P network and exposes the current order book and
 * trade statistics of all markets over a read-only HTTP/JSON API.
 * <p>
 * It reuses the same P2P-only application setup as the statistics node ({@code statsnode}): no
 * Bitcoin wallet and no GUI. All data is derived from {@code OfferBookService} (open offers) and
 * {@code TradeStatisticsManager} (trade statistics), both populated purely from the P2P network.
 */
@Slf4j
public class MarketsNodeMain extends ExecutableForAppWithP2p {
    public static void main(String[] args) {
        new MarketsNodeMain().execute(args);
    }

    private MarketsNode marketsNode;

    public MarketsNodeMain() {
        super("Bisq Marketsnode", "bisq-marketsnode", "bisq_marketsnode", Version.VERSION);
    }

    @Override
    protected void doExecute() {
        super.doExecute();

        checkMemory(config);
        keepRunning();
    }

    @Override
    protected void applyInjector() {
        super.applyInjector();
        marketsNode = new MarketsNode(injector);
    }

    @Override
    protected void startApplication() {
        super.startApplication();
        marketsNode.startApplication();
    }

    @Override
    protected void shutDownAdditionalServices() {
        if (marketsNode != null) {
            marketsNode.shutDown();
        }
    }
}
