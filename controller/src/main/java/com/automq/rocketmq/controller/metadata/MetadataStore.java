/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.automq.rocketmq.controller.metadata;

import apache.rocketmq.controller.v1.AssignmentStatus;
import apache.rocketmq.controller.v1.ConsumerGroup;
import apache.rocketmq.controller.v1.GroupType;
import apache.rocketmq.controller.v1.StreamRole;
import apache.rocketmq.controller.v1.Topic;
import apache.rocketmq.controller.v1.S3StreamObject;
import apache.rocketmq.controller.v1.S3WALObject;
import apache.rocketmq.controller.v1.StreamMetadata;
import com.automq.rocketmq.controller.exception.ControllerException;
import com.automq.rocketmq.controller.metadata.database.dao.Node;
import com.automq.rocketmq.controller.metadata.database.dao.QueueAssignment;
import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface MetadataStore extends Closeable {

    void start();

    /**
     * Register broker into metadata store and return broker epoch
     *
     * @return broker epoch
     * @throws ControllerException If there is an I/O error.
     */
    Node registerBrokerNode(String name, String address, String instanceId) throws ControllerException;

    void keepAlive(int nodeId, long epoch, boolean goingAway);

    long createTopic(String topicName, int queueNum) throws ControllerException;

    void deleteTopic(long topicId) throws ControllerException;

    CompletableFuture<Topic> describeTopic(Long topicId, String topicName);

    List<Topic> listTopics() throws ControllerException;

    /**
     * Check if current controller is playing leader role
     *
     * @return true if leader; false otherwise
     * @throws ControllerException If there is any I/O error
     */
    boolean isLeader() throws ControllerException;

    String leaderAddress() throws ControllerException;

    /**
     * List queue assignments according to criteria.
     *
     * @param topicId   Optional topic-id
     * @param srcNodeId Optional source node-id
     * @param dstNodeId Optional destination node-id
     * @param status    Optional queue assignment status
     * @return List of the queue assignments meeting the specified criteria
     */
    List<QueueAssignment> listAssignments(Long topicId, Integer srcNodeId, Integer dstNodeId,
        AssignmentStatus status);

    void reassignMessageQueue(long topicId, int queueId, int dstNodeId) throws ControllerException;

    void markMessageQueueAssignable(long topicId, int queueId) throws ControllerException;

    void commitOffset(long groupId, long topicId, int queueId, long offset) throws ControllerException;

    long createGroup(String groupName, int maxRetry, GroupType type, long dlq) throws ControllerException;

    CompletableFuture<StreamMetadata> getStream(long topicId, int queueId, Long groupId, StreamRole streamRole);

    CompletableFuture<ConsumerGroup> getGroup(long groupId);

    void trimStream(long streamId, long streamEpoch, long newStartOffset) throws ControllerException;

    CompletableFuture<StreamMetadata> openStream(long streamId, long streamEpoch);

    CompletableFuture<Void> closeStream(long streamId, long streamEpoch);

    CompletableFuture<List<StreamMetadata>> listOpenStreams(int nodeId, long epoch);

    long prepareS3Objects(int count, int ttlInMinutes) throws ControllerException;

    void commitWalObject(S3WALObject walObject, List<S3StreamObject> streamObjects, List<Long> compactedObjects) throws ControllerException;

    void commitStreamObject(S3StreamObject streamObject, List<Long> compactedObjects) throws ControllerException;

    List<S3WALObject> listWALObjects() throws ControllerException;

    List<S3WALObject> listWALObjects(long streamId, long startOffset, long endOffset, int limit);

    List<S3StreamObject> listStreamObjects(long streamId, long startOffset, long endOffset, int limit);

    long getOrCreateRetryStream(String groupName, long topicId, int queueId) throws ControllerException;
}
