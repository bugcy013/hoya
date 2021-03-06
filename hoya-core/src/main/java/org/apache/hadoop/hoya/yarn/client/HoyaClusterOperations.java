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

package org.apache.hadoop.hoya.yarn.client;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.hoya.api.ClusterDescription;
import org.apache.hadoop.hoya.api.ClusterNode;
import org.apache.hadoop.hoya.api.HoyaClusterProtocol;
import org.apache.hadoop.hoya.api.proto.Messages;
import org.apache.hadoop.hoya.exceptions.HoyaException;
import org.apache.hadoop.hoya.exceptions.NoSuchNodeException;
import org.apache.hadoop.hoya.exceptions.WaitTimeoutException;
import org.apache.hadoop.hoya.tools.Duration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.codehaus.jackson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Cluster operations at a slightly higher level than the RPC code
 */
public class HoyaClusterOperations {
  protected static final Logger
    log = LoggerFactory.getLogger(HoyaClusterOperations.class);
  
  private final HoyaClusterProtocol appMaster;

  public HoyaClusterOperations(HoyaClusterProtocol appMaster) {
    this.appMaster = appMaster;
  }

  /**
   * Get a node from the AM
   * @param appMaster AM
   * @param uuid uuid of node
   * @return deserialized node
   * @throws IOException IO problems
   * @throws NoSuchNodeException if the node isn't found
   */
  public ClusterNode getNode(String uuid)
    throws IOException, NoSuchNodeException, YarnException {
    Messages.GetNodeRequestProto req =
      Messages.GetNodeRequestProto.newBuilder().setUuid(uuid).build();
    Messages.GetNodeResponseProto node = appMaster.getNode(req);
    return ClusterNode.fromProtobuf(node.getClusterNode());
  }

  public List<ClusterNode> convertNodeWireToClusterNodes(List<Messages.RoleInstanceState> nodes)
    throws IOException {
    List<ClusterNode> nodeList = new ArrayList<ClusterNode>(nodes.size());
    for (Messages.RoleInstanceState node : nodes) {
      nodeList.add(ClusterNode.fromProtobuf(node));
    }
    return nodeList;
  }


  /**
   * Connect to a live cluster and get its current state
   * @param clustername the cluster name
   * @return its description
   */
  public ClusterDescription getClusterDescription(String clustername) throws
                                                                      YarnException,
                                                                      IOException {
    Messages.GetJSONClusterStatusRequestProto req =
      Messages.GetJSONClusterStatusRequestProto.newBuilder().build();
    Messages.GetJSONClusterStatusResponseProto resp =
      appMaster.getJSONClusterStatus(req);
    String statusJson = resp.getClusterSpec();
    try {
      return ClusterDescription.fromJson(statusJson);
    } catch (JsonParseException e) {
      log.error(
        "Exception " + e + " parsing:\n" + statusJson,
        e);
      throw e;
    }
  }

  /**
   * List all node UUIDs in a role
   * @param role role name or "" for all
   * @return an array of UUID strings
   * @throws IOException
   * @throws YarnException
   */
  public String[] listNodeUUIDsByRole(String role) throws
                                                   IOException,
                                                   YarnException {
    Collection<String> uuidList = innerListNodeUUIDSByRole(role);
    String[] uuids = new String[uuidList.size()];
    return uuidList.toArray(uuids);
  }

  public List<String> innerListNodeUUIDSByRole(String role) throws
                                                             IOException,
                                                             YarnException {
    Messages.ListNodeUUIDsByRoleRequestProto req =
      Messages.ListNodeUUIDsByRoleRequestProto
              .newBuilder()
              .setRole(role)
              .build();
    Messages.ListNodeUUIDsByRoleResponseProto resp =
      appMaster.listNodeUUIDsByRole(req);
    return resp.getUuidList();
  }

  /**
   * List all nodes in a role. This is a double round trip: once to list
   * the nodes in a role, another to get their details
   * @param role
   * @return an array of ContainerNode instances
   * @throws IOException
   * @throws YarnException
   */
  public List<ClusterNode> listClusterNodesInRole(String role) throws
                                                               IOException,
                                                               YarnException {

    Collection<String> uuidList = innerListNodeUUIDSByRole(role);
    Messages.GetClusterNodesRequestProto req =
      Messages.GetClusterNodesRequestProto
              .newBuilder()
              .addAllUuid(uuidList)
              .build();
    Messages.GetClusterNodesResponseProto resp = appMaster.getClusterNodes(req);
    return convertNodeWireToClusterNodes(resp.getClusterNodeList());
  }

  /**
   * Get the details on a list of uuids
   * @param uuids
   * @return a possibly empty list of node details
   * @throws IOException
   * @throws YarnException
   */
  @VisibleForTesting
  public List<ClusterNode> listClusterNodes(String[] uuids) throws
                                                            IOException,
                                                            YarnException {

    Messages.GetClusterNodesRequestProto req =
      Messages.GetClusterNodesRequestProto
              .newBuilder()
              .addAllUuid(Arrays.asList(uuids))
              .build();
    Messages.GetClusterNodesResponseProto resp = appMaster.getClusterNodes(req);
    return convertNodeWireToClusterNodes(resp.getClusterNodeList());
  }

  /**
   * Wait for an instance of a named role to be live (or past it in the lifecycle)
   * @param clustername cluster
   * @param role role to look for
   * @param timeout time to wait
   * @return the state. If still in CREATED, the cluster didn't come up
   * in the time period. If LIVE, all is well. If >LIVE, it has shut for a reason
   * @throws IOException IO
   * @throws HoyaException Hoya
   * @throws WaitTimeoutException if the wait timed out
   */
  @VisibleForTesting
  public int waitForRoleInstanceLive(String role, long timeout)
    throws WaitTimeoutException, IOException, YarnException {
    Duration duration = new Duration(timeout).start();
    boolean live = false;
    int state = ClusterDescription.STATE_CREATED;

    log.info("Waiting {} millis for a live node in role {}", timeout, role);
    while (!live) {
      // see if there is a node in that role yet
      List<String> uuids = innerListNodeUUIDSByRole(role);
      String[] containers = uuids.toArray(new String[uuids.size()]);
      int roleCount = containers.length;
      ClusterNode roleInstance = null;
      if (roleCount != 0) {

        // if there is, get the node
        roleInstance = getNode(containers[0]);
        if (roleInstance != null) {
          state = roleInstance.state;
          live = state >= ClusterDescription.STATE_LIVE;
        }
      }
      if (!live) {
        if (duration.getLimitExceeded()) {
          throw new WaitTimeoutException(
            String.format("Timeout after %d millis" +
                          " waiting for a live instance of type %s; " +
                          "instances found %d %s",
                          timeout, role, roleCount,
                          (roleInstance != null
                           ? (" instance -\n" + roleInstance.toString())
                           : "")
                         ));
        } else {
          try {
            Thread.sleep(1000);
          } catch (InterruptedException ignored) {
            // ignored
          }
        }
      }
    }
    return state;
  }
  
  public boolean flex(ClusterDescription clusterSpec) throws IOException, YarnException {
    Messages.FlexClusterRequestProto request =
      Messages.FlexClusterRequestProto.newBuilder()
              .setClusterSpec(clusterSpec.toJsonString())
              .build();
    Messages.FlexClusterResponseProto response =
      appMaster.flexCluster(request);
    return response.getResponse();
  }
}
