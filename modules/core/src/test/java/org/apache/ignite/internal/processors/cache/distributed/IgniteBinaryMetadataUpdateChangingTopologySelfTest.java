/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.distributed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.IgniteNodeAttributes;
import org.apache.ignite.internal.managers.communication.GridIoMessage;
import org.apache.ignite.internal.processors.cache.distributed.near.GridNearTxPrepareResponse;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.lang.IgniteInClosure;
import org.apache.ignite.plugin.extensions.communication.Message;
import org.apache.ignite.resources.LoggerResource;
import org.apache.ignite.spi.IgniteSpiException;
import org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.jetbrains.annotations.Nullable;

/**
 * Tests specific scenario when binary metadata should be updated from a system thread
 * and topology has been already changed since the original transaction start.
 */
public class IgniteBinaryMetadataUpdateChangingTopologySelfTest extends GridCommonAbstractTest {
    /** */
    private static final TcpDiscoveryIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder(true);

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        cfg.setMarshaller(null);

        CacheConfiguration ccfg = new CacheConfiguration("cache");

        ccfg.setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL);
        ccfg.setBackups(1);

        cfg.setCacheConfiguration(ccfg);

        TcpDiscoverySpi discoSpi = new TcpDiscoverySpi();

        discoSpi.setIpFinder(IP_FINDER);

        cfg.setDiscoverySpi(discoSpi);

        cfg.setCommunicationSpi(new TestCommunicationSpi());

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        startGrids(4);
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        stopAllGrids();
    }

    /**
     * @throws Exception If failed.
     */
    public void testNoDeadlockOptimistic() throws Exception {
        int key1 = primaryKey(ignite(1).cache("cache"));
        int key2 = primaryKey(ignite(2).cache("cache"));

        TestCommunicationSpi spi = (TestCommunicationSpi)ignite(1).configuration().getCommunicationSpi();

        spi.blockMessages(GridNearTxPrepareResponse.class, ignite(0).cluster().localNode().id());

        IgniteCache<Object, Object> cache = ignite(0).cache("cache").withAsync();

        cache.putAll(F.asMap(key1, "val1", key2, new TestValue()));

        try {
            Thread.sleep(500);

            IgniteInternalFuture<Void> fut = GridTestUtils.runAsync(new Callable<Void>() {
                @Override public Void call() throws Exception {
                    startGrid(4);

                    return null;
                }
            });

            Thread.sleep(500);

            spi.stopBlock();

            cache.future().get();

            fut.get();
        }
        finally {
            stopGrid(4);
        }
    }

    /**
     *
     */
    private static class TestCommunicationSpi extends TcpCommunicationSpi {
        /** */
        @LoggerResource
        private IgniteLogger log;

        /** */
        private List<T2<ClusterNode, GridIoMessage>> blockedMsgs = new ArrayList<>();

        /** */
        private Map<Class<?>, Set<UUID>> blockCls = new HashMap<>();

        /** */
        private Class<?> recordCls;

        /** */
        private List<Object> recordedMsgs = new ArrayList<>();

        /** {@inheritDoc} */
        @Override public void sendMessage(ClusterNode node, Message msg, IgniteInClosure<IgniteException> ackClosure)
            throws IgniteSpiException {
            if (msg instanceof GridIoMessage) {
                Object msg0 = ((GridIoMessage)msg).message();

                synchronized (this) {
                    if (recordCls != null && msg0.getClass().equals(recordCls))
                        recordedMsgs.add(msg0);

                    Set<UUID> blockNodes = blockCls.get(msg0.getClass());

                    if (F.contains(blockNodes, node.id())) {
                        log.info("Block message [node=" + node.attribute(IgniteNodeAttributes.ATTR_GRID_NAME) +
                            ", msg=" + msg0 + ']');

                        blockedMsgs.add(new T2<>(node, (GridIoMessage)msg));

                        return;
                    }
                }
            }

            super.sendMessage(node, msg, ackClosure);
        }

        /**
         * @param recordCls Message class to record.
         */
        void record(@Nullable Class<?> recordCls) {
            synchronized (this) {
                this.recordCls = recordCls;
            }
        }

        /**
         * @return Recorded messages.
         */
        List<Object> recordedMessages() {
            synchronized (this) {
                List<Object> msgs = recordedMsgs;

                recordedMsgs = new ArrayList<>();

                return msgs;
            }
        }

        /**
         * @param cls Message class.
         * @param nodeId Node ID.
         */
        void blockMessages(Class<?> cls, UUID nodeId) {
            synchronized (this) {
                Set<UUID> set = blockCls.get(cls);

                if (set == null) {
                    set = new HashSet<>();

                    blockCls.put(cls, set);
                }

                set.add(nodeId);
            }
        }

        /**
         *
         */
        void stopBlock() {
            synchronized (this) {
                blockCls.clear();

                for (T2<ClusterNode, GridIoMessage> msg : blockedMsgs) {
                    ClusterNode node = msg.get1();

                    log.info("Send blocked message: [node=" + node.attribute(IgniteNodeAttributes.ATTR_GRID_NAME) +
                        ", msg=" + msg.get2().message() + ']');

                    sendMessage(msg.get1(), msg.get2());
                }

                blockedMsgs.clear();
            }
        }
    }

    private static class TestValue {
        /** Field1. */
        private String field1;
    }
}
