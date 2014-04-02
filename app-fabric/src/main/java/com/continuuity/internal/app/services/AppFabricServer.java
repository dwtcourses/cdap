/*
 * Copyright 2012-2013 Continuuity,Inc. All Rights Reserved.
 */

package com.continuuity.internal.app.services;

import com.continuuity.app.services.AppFabricService;
import com.continuuity.common.conf.CConfiguration;
import com.continuuity.common.conf.Constants;
import com.continuuity.gateway.handlers.AppFabricHttpHandler;
import com.continuuity.http.HttpHandler;
import com.continuuity.http.NettyHttpService;
import com.continuuity.internal.app.runtime.schedule.SchedulerService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.apache.twill.common.Threads;
import org.apache.twill.discovery.Discoverable;
import org.apache.twill.discovery.DiscoveryService;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.apache.thrift.server.TThreadedSelectorServer;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.immutable.Stream;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AppFabric Server that implements {@link AbstractExecutionThreadService}.
 */
public class AppFabricServer extends AbstractExecutionThreadService {
  private static final int THREAD_COUNT = 2;

  private final AppFabricService.Iface service;
  private final int port;
  private final DiscoveryService discoveryService;
  private final InetAddress hostname;
  private final SchedulerService schedulerService;

  private TThreadedSelectorServer server;
  private NettyHttpService httpService;
  //private final String httpHostName;
  private final int httpPort;
  private ExecutorService executor;
  private Set<HttpHandler> handlers;
  private static final Logger LOG = LoggerFactory.getLogger(AppFabricServer.class);
  /**
   * Construct the AppFabricServer with service factory and configuration coming from guice injection.
   */
  @Inject
  public AppFabricServer(AppFabricServiceFactory serviceFactory, CConfiguration configuration,
                         DiscoveryService discoveryService, SchedulerService schedulerService,
                         @Named(Constants.AppFabric.SERVER_ADDRESS) InetAddress hostname,
                         @Named("httphandler")Set<HttpHandler> handlers) {
    this.hostname = hostname;
    this.discoveryService = discoveryService;
    this.schedulerService = schedulerService;
    this.service = serviceFactory.create(schedulerService);
    this.port = configuration.getInt(Constants.AppFabric.SERVER_PORT, Constants.AppFabric.DEFAULT_THRIFT_PORT);
    //this.port = Constants.AppFabric.DEFAULT_THRIFT_PORT;
    //this.httpHostName = configuration.get(Constants.AppFabric.SERVER_ADDRESS,
    //                                      Constants.AppFabric.DEFAULT_SERVER_ADDRESS);
    //this.httpHostName = hostname.getHostName();
    this.httpPort = Constants.AppFabric.DEFAULT_SERVER_PORT;
    this.handlers = handlers;
    //this.httpPort = configuration.getInt(Constants.AppFabric.SERVER_PORT, Constants.AppFabric.DEFAULT_SERVER_PORT);
  }

  /**
   * Configures the AppFabricService pre-start.
   */
  @Override
  protected void startUp() throws Exception {

    executor = Executors.newFixedThreadPool(THREAD_COUNT, Threads.createDaemonThreadFactory("app-fabric-server-%d"));
    schedulerService.start();
    // Register with discovery service.
    InetSocketAddress socketAddress = new InetSocketAddress(hostname, port);
    InetAddress address = socketAddress.getAddress();
    if (address.isAnyLocalAddress()) {
      address = InetAddress.getLocalHost();
    }
    final InetSocketAddress finalSocketAddress = new InetSocketAddress(address, port);

    discoveryService.register(new Discoverable() {
      @Override
      public String getName() {
        return Constants.Service.APP_FABRIC;
      }

      @Override
      public InetSocketAddress getSocketAddress() {
        return finalSocketAddress;
      }
    });

    //Register netty-http with discovery service
    InetSocketAddress httpSocketAddress = new InetSocketAddress(hostname, httpPort);
    InetAddress httpAddress = socketAddress.getAddress();
    if (httpAddress.isAnyLocalAddress()) {
      httpAddress = InetAddress.getLocalHost();
    }
    final InetSocketAddress finalHttpSocketAddress = new InetSocketAddress(httpAddress, httpPort);

    discoveryService.register(new Discoverable() {
      @Override
      public String getName() {
        return Constants.Service.APP_FABRIC_HTTP;
      }

      @Override
      public InetSocketAddress getSocketAddress() {
        return finalHttpSocketAddress;
      }
    });

    TThreadedSelectorServer.Args options = new TThreadedSelectorServer.Args(new TNonblockingServerSocket(socketAddress))
      .executorService(executor)
      .processor(new AppFabricService.Processor<AppFabricService.Iface>(service))
      .workerThreads(THREAD_COUNT);
    options.maxReadBufferBytes = Constants.Thrift.DEFAULT_MAX_READ_BUFFER;
    server = new TThreadedSelectorServer(options);
    httpService = NettyHttpService.builder()
      .setHost(hostname.getHostName())
      .setPort(httpPort)
      .addHttpHandlers(handlers)
      .build();

  }

  /**
   * Runs the AppFabricServer.
   * <p>
   *   It's run on a different thread.
   * </p>
   */
  @Override
  protected void run() throws Exception {
    httpService.startAndWait();
    server.serve();

  }

  /**
   * Invoked during shutdown of the thread.
   */
  protected void triggerShutdown() {
    schedulerService.stopAndWait();
    executor.shutdownNow();
    server.stop();
    httpService.stopAndWait();
  }

  public AppFabricService.Iface getService() {
    return service;
  }
}
