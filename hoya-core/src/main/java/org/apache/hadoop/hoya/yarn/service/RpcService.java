/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hoya.yarn.service;

import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.service.AbstractService;

import java.net.InetSocketAddress;

/**
 * A YARN service that maps the start/stop lifecycle of an RPC server
 * to the YARN service lifecycle
 */
public class RpcService extends AbstractService {

  /** RPC server*/
  private final Server server;

  /**
   * Construct an instance
   * @param server server to manger
   */
  public RpcService(Server server) {
    super("RpcService");
    this.server = server;
  }

  public Server getServer() {
    return server;
  }

  public InetSocketAddress getConnectAddress() {
    return NetUtils.getConnectAddress(server);
  }

  @Override
  protected void serviceStart() throws Exception {
    super.serviceStart();
    server.start();
  }

  @Override
  protected void serviceStop() throws Exception {
    if (server != null) {
      server.stop();
    }
  }
}
