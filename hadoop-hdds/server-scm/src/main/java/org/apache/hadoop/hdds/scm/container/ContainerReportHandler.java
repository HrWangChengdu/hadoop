/**
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
package org.apache.hadoop.hdds.scm.container;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.ContainerReportsProto;
import org.apache.hadoop.hdds.scm.container.replication.ReplicationActivityStatus;
import org.apache.hadoop.hdds.scm.container.replication.ReplicationRequest;
import org.apache.hadoop.hdds.scm.events.SCMEvents;
import org.apache.hadoop.hdds.scm.node.NodeManager;
import org.apache.hadoop.hdds.scm.node.states.ReportResult;
import org.apache.hadoop.hdds.scm.server.SCMDatanodeHeartbeatDispatcher.ContainerReportFromDatanode;
import org.apache.hadoop.hdds.server.events.EventHandler;
import org.apache.hadoop.hdds.server.events.EventPublisher;

import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles container reports from datanode.
 */
public class ContainerReportHandler implements
    EventHandler<ContainerReportFromDatanode> {

  private static final Logger LOG =
      LoggerFactory.getLogger(ContainerReportHandler.class);

  private final NodeManager nodeManager;
  private final ContainerManager containerManager;
  private ReplicationActivityStatus replicationStatus;

  public ContainerReportHandler(ContainerManager containerManager,
      NodeManager nodeManager,
      ReplicationActivityStatus replicationActivityStatus) {
    Preconditions.checkNotNull(containerManager);
    Preconditions.checkNotNull(nodeManager);
    Preconditions.checkNotNull(replicationActivityStatus);
    this.nodeManager = nodeManager;
    this.containerManager = containerManager;
    this.replicationStatus = replicationActivityStatus;
  }

  @Override
  public void onMessage(ContainerReportFromDatanode containerReportFromDatanode,
      EventPublisher publisher) {

    DatanodeDetails datanodeOrigin =
        containerReportFromDatanode.getDatanodeDetails();

    ContainerReportsProto containerReport =
        containerReportFromDatanode.getReport();
    try {

      //update state in container db and trigger close container events
      containerManager
          .processContainerReports(datanodeOrigin, containerReport);

      Set<ContainerID> containerIds = containerReport.getReportsList().stream()
          .map(StorageContainerDatanodeProtocolProtos
              .ContainerReplicaProto::getContainerID)
          .map(ContainerID::new)
          .collect(Collectors.toSet());

      ReportResult<ContainerID> reportResult = nodeManager
          .processContainerReport(datanodeOrigin.getUuid(), containerIds);

      //we have the report, so we can update the states for the next iteration.
      nodeManager
          .setContainersForDatanode(datanodeOrigin.getUuid(), containerIds);

      for (ContainerID containerID : reportResult.getMissingEntries()) {
        final ContainerReplica replica = ContainerReplica.newBuilder()
            .setContainerID(containerID)
            .setDatanodeDetails(datanodeOrigin)
            .build();
        containerManager
            .removeContainerReplica(containerID, replica);
        checkReplicationState(containerID, publisher);
      }

      for (ContainerID containerID : reportResult.getNewEntries()) {
        final ContainerReplica replica = ContainerReplica.newBuilder()
            .setContainerID(containerID)
            .setDatanodeDetails(datanodeOrigin)
            .build();
        containerManager.updateContainerReplica(containerID, replica);
        checkReplicationState(containerID, publisher);
      }

    } catch (IOException e) {
      //TODO: stop all the replication?
      LOG.error("Error on processing container report from datanode {}",
          datanodeOrigin, e);
    }

  }

  private void checkReplicationState(ContainerID containerID,
      EventPublisher publisher) {
    try {
      ContainerInfo container = containerManager.getContainer(containerID);
      replicateIfNeeded(container, publisher);
    } catch (ContainerNotFoundException ex) {
      LOG.warn(
          "Container is missing from containerStateManager. Can't request "
              + "replication. {}",
          containerID);
    }

  }

  private void replicateIfNeeded(ContainerInfo container,
      EventPublisher publisher) throws ContainerNotFoundException {
    if (!container.isOpen() && replicationStatus.isReplicationEnabled()) {
      final int existingReplicas = containerManager
          .getContainerReplicas(container.containerID()).size();
      final int expectedReplicas = container.getReplicationFactor().getNumber();
      if (existingReplicas != expectedReplicas) {
        publisher.fireEvent(SCMEvents.REPLICATE_CONTAINER,
            new ReplicationRequest(container.getContainerID(),
                existingReplicas, expectedReplicas));
      }
    }
  }
}
