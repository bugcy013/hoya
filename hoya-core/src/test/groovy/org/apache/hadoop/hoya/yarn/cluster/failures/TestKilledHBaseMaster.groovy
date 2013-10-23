/*
 * Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.hoya.yarn.cluster.failures

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.ClusterStatus
import org.apache.hadoop.hbase.HConstants
import org.apache.hadoop.hbase.ServerName
import org.apache.hadoop.hoya.api.ClusterDescription
import org.apache.hadoop.hoya.providers.hbase.HBaseKeys
import org.apache.hadoop.hoya.yarn.client.HoyaClient
import org.apache.hadoop.hoya.yarn.providers.hbase.HBaseMiniClusterTestBase
import org.apache.hadoop.yarn.service.launcher.ServiceLauncher
import org.junit.Test

/**
 * test create a live region service
 */
@CompileStatic
@Slf4j
class TestKilledHBaseMaster extends HBaseMiniClusterTestBase {

  @Test
  public void testKilledHBaseMaster() throws Throwable {
    String clustername = "TestKilledHBaseMaster"
    int regionServerCount = 1
    createMiniCluster(clustername, createConfiguration(), 1, 1, 1, true, true)
    describe("Kill the hbase master and expect a restart");

    //now launch the cluster
    ServiceLauncher launcher = createHBaseCluster(clustername, regionServerCount, [], true, true)
    HoyaClient hoyaClient = (HoyaClient) launcher.service
    addToTeardown(hoyaClient);
    ClusterDescription status = hoyaClient.getClusterStatus(clustername)

    ClusterStatus clustat = basicHBaseClusterStartupSequence(hoyaClient)


    status = waitForHoyaWorkerCount(hoyaClient, regionServerCount, HBASE_CLUSTER_STARTUP_TO_LIVE_TIME)
    //get the hbase status
    ClusterStatus hbaseStat = waitForHBaseRegionServerCount(hoyaClient, clustername, regionServerCount, HBASE_CLUSTER_STARTUP_TO_LIVE_TIME)
    ServerName master = hbaseStat.master
    log.info("HBase master providing status information at {}",
             hbaseStat.master)
    
    Configuration clientConf = createHBaseConfiguration(hoyaClient)
    clientConf.setInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER, 10);
    killAllMasterServers();
    status = waitForRoleCount(
        hoyaClient, HBaseKeys.ROLE_MASTER, 1, HBASE_CLUSTER_STARTUP_TO_LIVE_TIME)
    hbaseStat = waitForHBaseRegionServerCount(
        hoyaClient,
        clustername,
        regionServerCount,
        HBASE_CLUSTER_STARTUP_TO_LIVE_TIME)

    ServerName master2 = hbaseStat.master
    log.info("HBase master providing status information again at {}",
             master2)
    assert master2 != master
  }


}
