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

package org.apache.hadoop.hoya.yarn.model.history

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.apache.hadoop.hoya.api.RoleKeys
import org.apache.hadoop.hoya.yarn.appmaster.state.ContainerPriority
import org.apache.hadoop.hoya.yarn.appmaster.state.NodeEntry
import org.apache.hadoop.hoya.yarn.appmaster.state.NodeInstance
import org.apache.hadoop.hoya.yarn.appmaster.state.NodeMap
import org.apache.hadoop.hoya.yarn.appmaster.state.RoleHistory
import org.apache.hadoop.hoya.yarn.appmaster.state.RoleInstance
import org.apache.hadoop.hoya.yarn.model.mock.BaseMockAppStateTest
import org.apache.hadoop.hoya.yarn.model.mock.MockContainer
import org.apache.hadoop.hoya.yarn.model.mock.MockFactory
import org.apache.hadoop.hoya.yarn.model.mock.MockNodeId
import org.apache.hadoop.yarn.api.records.Resource
import org.apache.hadoop.yarn.client.api.AMRMClient
import org.junit.Before
import org.junit.Test

/**
 * Test container events at the role history level -one below
 * the App State
 */
@Slf4j
@CompileStatic
class TestRoleHistoryContainerEvents extends BaseMockAppStateTest {

  @Override
  String getTestName() {
    return "TestRoleHistoryContainerEvents"
  }

  NodeInstance age1Active4 = nodeInstance(1, 4, 0, 0)
  NodeInstance age2Active2 = nodeInstance(2, 2, 0, 1)
  NodeInstance age3Active0 = nodeInstance(3, 0, 0, 0)
  NodeInstance age4Active1 = nodeInstance(4, 1, 0, 0)
  NodeInstance age2Active0 = nodeInstance(2, 0, 0, 0)
  NodeInstance empty = new NodeInstance("empty", MockFactory.ROLE_COUNT)

  List<NodeInstance> nodes = [age2Active2, age2Active0, age4Active1, age1Active4, age3Active0]
  RoleHistory roleHistory = new RoleHistory(MockFactory.ROLES)

  Resource resource

  @Before
  public void setupRH() {
    roleHistory.onStart(fs, historyPath)
    roleHistory.insert(nodes)
    roleHistory.buildAvailableNodeLists();
    resource = Resource.newInstance(RoleKeys.DEF_YARN_CORES,
                                    RoleKeys.DEF_YARN_MEMORY);
  }

  @Test
  public void testFindAndCreate() throws Throwable {
    int role = 0
    AMRMClient.ContainerRequest request =
        roleHistory.requestNode(role, resource);

    List<String> nodes = request.getNodes()
    assert nodes != null
    assert nodes.size() == 1
    String hostname = nodes[0]
    assert hostname == age3Active0.hostname

    //build a container
    MockContainer container = factory.newContainer()
    container.nodeId = new MockNodeId(hostname, 0)
    container.priority = request.getPriority()
    roleHistory.onContainerAssigned(container);

    NodeMap nodemap = roleHistory.cloneNodemap();
    NodeInstance allocated = nodemap.get(hostname)
    NodeEntry roleEntry = allocated.get(role)
    assert roleEntry.starting == 1
    assert !roleEntry.available
    RoleInstance ri = new RoleInstance(container);
    ri.buildUUID();
    //start it
    roleHistory.onContainerStartSubmitted(container, ri)
    //later, declare that it started
    roleHistory.onContainerStarted(container)
    assert roleEntry.starting == 0
    assert !roleEntry.available
    assert roleEntry.active == 1
    assert roleEntry.live == 1
  }

  @Test
  public void testCreateAndRelease() throws Throwable {
    int role = 1

    //verify it is empty
    assert roleHistory.findNodesForRelease(role, 1).isEmpty()

    AMRMClient.ContainerRequest request =
        roleHistory.requestNode(role, resource);

    List<String> nodes = request.getNodes()
    assert nodes == null

    //pick an idle host
    String hostname = age3Active0.hostname;

    //build a container
    MockContainer container = factory.newContainer()
    container.nodeId = new MockNodeId(hostname, 0)
    container.priority = request.getPriority()
    roleHistory.onContainerAssigned(container);

    NodeMap nodemap = roleHistory.cloneNodemap();
    NodeInstance allocated = nodemap.get(hostname)
    NodeEntry roleEntry = allocated.get(role)
    assert roleEntry.starting == 1
    assert !roleEntry.available
    RoleInstance ri = new RoleInstance(container);
    ri.buildUUID();
    //start it
    roleHistory.onContainerStartSubmitted(container, ri)
    //later, declare that it started
    roleHistory.onContainerStarted(container)
    assert roleEntry.starting == 0
    assert !roleEntry.available
    assert roleEntry.active == 1
    assert roleEntry.live == 1

    // now pick that instance to destroy

    List<NodeInstance> forRelease = roleHistory.findNodesForRelease(role, 1)
    assert forRelease.size() == 1
    NodeInstance target = forRelease[0]
    assert target == allocated
    roleHistory.onContainerReleaseSubmitted(container);
    assert roleEntry.releasing == 1
    assert roleEntry.live == 1
    assert roleEntry.active == 0

    // release completed
    roleHistory.onReleaseCompleted(container)
    assert roleEntry.releasing == 0
    assert roleEntry.live == 0
    assert roleEntry.active == 0

    // verify it is empty
    assert roleHistory.findNodesForRelease(role, 1).isEmpty()

    // ask for a container and expect to get the recently released one
    AMRMClient.ContainerRequest request2 =
        roleHistory.requestNode(role, resource);

    List<String> nodes2 = request2.getNodes()
    assert nodes2 != null
    String hostname2 = nodes2[0]

    //pick an idle host
    assert hostname2 == age3Active0.hostname;
  }


  @Test
  public void testStartWithoutWarning() throws Throwable {

    int role = 0
    //pick an idle host
    String hostname = age3Active0.hostname;
    //build a container
    MockContainer container = factory.newContainer()
    container.nodeId = new MockNodeId(hostname, 0)
    container.priority = ContainerPriority.createPriority(0, false)
    
    NodeMap nodemap = roleHistory.cloneNodemap();
    NodeInstance allocated = nodemap.get(hostname)
    NodeEntry roleEntry = allocated.get(role)

    RoleInstance ri = new RoleInstance(container);
    ri.buildUUID();
    //tell RH that it started
    roleHistory.onContainerStarted(container)
    assert roleEntry.starting == 0
    assert !roleEntry.available
    assert roleEntry.active == 1
    assert roleEntry.live == 1
  }

  @Test
  public void testStartFailed() throws Throwable {
    int role = 0
    AMRMClient.ContainerRequest request =
        roleHistory.requestNode(role, resource);

    String hostname = request.getNodes()[0]
    assert hostname == age3Active0.hostname

    //build a container
    MockContainer container = factory.newContainer()
    container.nodeId = new MockNodeId(hostname, 0)
    container.priority = request.getPriority()
    roleHistory.onContainerAssigned(container);

    NodeMap nodemap = roleHistory.cloneNodemap();
    NodeInstance allocated = nodemap.get(hostname)
    NodeEntry roleEntry = allocated.get(role)
    assert roleEntry.starting == 1
    assert !roleEntry.available
    RoleInstance ri = new RoleInstance(container);
    ri.buildUUID();
    //start it
    roleHistory.onContainerStartSubmitted(container, ri)
    //later, declare that it failed on startup
    assert !roleHistory.onNodeManagerContainerStartFailed(container)
    assert roleEntry.starting == 0
    assert roleEntry.startFailed == 1
    assert roleEntry.failed == 1
    assert roleEntry.available
    assert roleEntry.active == 0
    assert roleEntry.live == 0
  }
  
  @Test
  public void testStartFailedWithoutWarning() throws Throwable {
    int role = 0
    AMRMClient.ContainerRequest request =
        roleHistory.requestNode(role, resource);

    String hostname = request.getNodes()[0]
    assert hostname == age3Active0.hostname

    //build a container
    MockContainer container = factory.newContainer()
    container.nodeId = new MockNodeId(hostname, 0)
    container.priority = request.getPriority()

    NodeMap nodemap = roleHistory.cloneNodemap();
    NodeInstance allocated = nodemap.get(hostname)
    NodeEntry roleEntry = allocated.get(role)

    assert !roleHistory.onNodeManagerContainerStartFailed(container)
    assert roleEntry.starting == 0
    assert roleEntry.startFailed == 1
    assert roleEntry.failed == 1
    assert roleEntry.available
    assert roleEntry.active == 0
    assert roleEntry.live == 0
  }
  
  @Test
  public void testContainerFailed() throws Throwable {
    describe("fail a container without declaring it as starting")

    int role = 0
    AMRMClient.ContainerRequest request =
        roleHistory.requestNode(role, resource);

    String hostname = request.getNodes()[0]
    assert hostname == age3Active0.hostname

    //build a container
    MockContainer container = factory.newContainer()
    container.nodeId = new MockNodeId(hostname, 0)
    container.priority = request.getPriority()
    roleHistory.onContainerAssigned(container);

    NodeMap nodemap = roleHistory.cloneNodemap();
    NodeInstance allocated = nodemap.get(hostname)
    NodeEntry roleEntry = allocated.get(role)
    assert roleEntry.starting == 1
    assert !roleEntry.available
    RoleInstance ri = new RoleInstance(container);
    ri.buildUUID();
    //start it
    roleHistory.onContainerStartSubmitted(container, ri)
    roleHistory.onContainerStarted(container)

    //later, declare that it failed
    roleHistory.onFailedContainer(container, false)
    assert roleEntry.starting == 0
    assert roleEntry.available
    assert roleEntry.active == 0
    assert roleEntry.live == 0
  }
  
  @Test
  public void testContainerFailedWithoutWarning() throws Throwable {
    describe( "fail a container without declaring it as starting")
    int role = 0
    AMRMClient.ContainerRequest request =
        roleHistory.requestNode(role, resource);

    String hostname = request.getNodes()[0]
    assert hostname == age3Active0.hostname

    //build a container
    MockContainer container = factory.newContainer()
    container.nodeId = new MockNodeId(hostname, 0)
    container.priority = request.getPriority()


    NodeMap nodemap = roleHistory.cloneNodemap();
    NodeInstance allocated = nodemap.get(hostname)
    NodeEntry roleEntry = allocated.get(role)
    assert roleEntry.available
    roleHistory.onFailedContainer(container, false)
    assert roleEntry.starting == 0
    assert roleEntry.failed == 1
    assert roleEntry.available
    assert roleEntry.active == 0
    assert roleEntry.live == 0
  }
}
