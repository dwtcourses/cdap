/*
 * Copyright 2012-2014 Continuuity, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.continuuity.data2.transaction.distributed;

import com.continuuity.common.rpc.ThriftRPCServer;
import com.continuuity.data2.transaction.distributed.thrift.TTransactionServer;
import com.continuuity.data2.transaction.inmemory.InMemoryTransactionManager;
import com.continuuity.data2.transaction.inmemory.InMemoryTransactionService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.inject.Inject;
import com.google.inject.Provider;
import org.apache.hadoop.conf.Configuration;
import org.apache.twill.api.ElectionHandler;
import org.apache.twill.common.ServiceListenerAdapter;
import org.apache.twill.discovery.DiscoveryService;
import org.apache.twill.internal.zookeeper.LeaderElection;
import org.apache.twill.zookeeper.ZKClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *
 */
public final class TransactionService extends InMemoryTransactionService {
  private static final Logger LOG = LoggerFactory.getLogger(TransactionService.class);
  private LeaderElection leaderElection;
  private final ZKClient zkClient;

  private ThriftRPCServer<TransactionServiceThriftHandler, TTransactionServer> server;

  @Inject
  public TransactionService(Configuration conf,
                            ZKClient zkClient,
                            DiscoveryService discoveryService,
                            Provider<InMemoryTransactionManager> txManagerProvider) {
    super(conf, discoveryService, txManagerProvider);
    this.zkClient = zkClient;
  }

  @Override
  protected InetSocketAddress getAddress() {
    return server.getBindAddress();
  }

  @Override
  protected void doStart() {
    leaderElection = new LeaderElection(zkClient, "/tx.service/leader", new ElectionHandler() {
      @Override
      public void leader() {
        // if the txManager fails, we should stop the server
        txManager = txManagerProvider.get();
        txManager.addListener(new ServiceListenerAdapter() {
          @Override
          public void failed(State from, Throwable failure) {
            LOG.error("Transaction manager aborted, stopping transaction service");
            stopAndWait();
          }
        }, MoreExecutors.sameThreadExecutor());

        server = ThriftRPCServer.builder(TTransactionServer.class)
          .setHost(address)
          .setWorkerThreads(threads)
          .setMaxReadBufferBytes(maxReadBufferBytes)
          .setIOThreads(ioThreads)
          .build(new TransactionServiceThriftHandler(txManager));
        try {
          server.startAndWait();
          doRegister();
          LOG.info("Transaction Thrift Service started successfully on " + getAddress());
        } catch (Throwable t) {
          LOG.info("Transaction Thrift Service didn't start on " + server.getBindAddress());
          leaderElection.stop();
          notifyFailed(t);
        }
      }

      @Override
      public void follower() {
        undoRegiser();
        if (server != null && server.isRunning()) {
          server.stopAndWait();
        }
      }
    });
    leaderElection.start();

    notifyStarted();
  }

  @Override
  protected void doStop() {
    if (leaderElection != null) {
      // NOTE: if was a leader this will cause loosing of leadership which in callback above will
      //       de-register service in discovery service and stop the service if needed
      try {
        Uninterruptibles.getUninterruptibly(leaderElection.stop(), 5, TimeUnit.SECONDS);
      } catch (TimeoutException te) {
        LOG.warn("Timed out waiting for leader election cancellation to complete");
      } catch (ExecutionException e) {
        LOG.error("Exception when cancelling leader election.", e);
      }
    }

    notifyStopped();
  }
}
