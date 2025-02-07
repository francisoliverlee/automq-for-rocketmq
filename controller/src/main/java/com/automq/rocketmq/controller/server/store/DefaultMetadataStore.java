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

package com.automq.rocketmq.controller.server.store;

import apache.rocketmq.controller.v1.AssignmentStatus;
import apache.rocketmq.controller.v1.CloseStreamRequest;
import apache.rocketmq.controller.v1.Cluster;
import apache.rocketmq.controller.v1.ClusterSummary;
import apache.rocketmq.controller.v1.Code;
import apache.rocketmq.controller.v1.ConsumerGroup;
import apache.rocketmq.controller.v1.CreateGroupRequest;
import apache.rocketmq.controller.v1.CreateTopicRequest;
import apache.rocketmq.controller.v1.DescribeClusterRequest;
import apache.rocketmq.controller.v1.GroupStatus;
import apache.rocketmq.controller.v1.ListOpenStreamsReply;
import apache.rocketmq.controller.v1.ListOpenStreamsRequest;
import apache.rocketmq.controller.v1.OpenStreamReply;
import apache.rocketmq.controller.v1.OpenStreamRequest;
import apache.rocketmq.controller.v1.StreamMetadata;
import apache.rocketmq.controller.v1.StreamRole;
import apache.rocketmq.controller.v1.StreamState;
import apache.rocketmq.controller.v1.TerminationStage;
import apache.rocketmq.controller.v1.UpdateGroupRequest;
import apache.rocketmq.controller.v1.UpdateTopicRequest;
import com.automq.rocketmq.common.PrefixThreadFactory;
import com.automq.rocketmq.common.api.DataStore;
import com.automq.rocketmq.common.config.ControllerConfig;
import com.automq.rocketmq.controller.ControllerClient;
import com.automq.rocketmq.controller.exception.ControllerException;
import com.automq.rocketmq.controller.MetadataStore;
import com.automq.rocketmq.controller.server.store.impl.GroupManager;
import com.automq.rocketmq.controller.server.store.impl.StreamManager;
import com.automq.rocketmq.controller.server.store.impl.TopicManager;
import com.automq.rocketmq.controller.server.tasks.ScanGroupTask;
import com.automq.rocketmq.controller.server.tasks.ScanStreamTask;
import com.automq.rocketmq.metadata.dao.Group;
import com.automq.rocketmq.metadata.dao.GroupCriteria;
import com.automq.rocketmq.metadata.dao.GroupProgress;
import com.automq.rocketmq.metadata.dao.Lease;
import com.automq.rocketmq.metadata.dao.Node;
import com.automq.rocketmq.metadata.dao.QueueAssignment;
import com.automq.rocketmq.metadata.dao.Range;
import com.automq.rocketmq.metadata.dao.Stream;
import com.automq.rocketmq.metadata.dao.StreamCriteria;
import com.automq.rocketmq.metadata.dao.Topic;
import com.automq.rocketmq.metadata.mapper.GroupMapper;
import com.automq.rocketmq.metadata.mapper.GroupProgressMapper;
import com.automq.rocketmq.metadata.mapper.LeaseMapper;
import com.automq.rocketmq.metadata.mapper.NodeMapper;
import com.automq.rocketmq.metadata.mapper.QueueAssignmentMapper;
import com.automq.rocketmq.metadata.mapper.RangeMapper;
import com.automq.rocketmq.metadata.mapper.StreamMapper;
import com.automq.rocketmq.controller.server.tasks.HeartbeatTask;
import com.automq.rocketmq.controller.server.tasks.LeaseTask;
import com.automq.rocketmq.controller.server.tasks.ReclaimS3ObjectTask;
import com.automq.rocketmq.controller.server.tasks.RecycleGroupTask;
import com.automq.rocketmq.controller.server.tasks.RecycleS3Task;
import com.automq.rocketmq.controller.server.tasks.RecycleTopicTask;
import com.automq.rocketmq.controller.server.tasks.ScanAssignmentTask;
import com.automq.rocketmq.controller.server.tasks.ScanNodeTask;
import com.automq.rocketmq.controller.server.tasks.ScanTopicTask;
import com.automq.rocketmq.controller.server.tasks.ScanYieldingQueueTask;
import com.automq.rocketmq.controller.server.tasks.SchedulerTask;
import com.google.common.base.Strings;
import com.google.protobuf.Timestamp;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultMetadataStore implements MetadataStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultMetadataStore.class);

    private final ControllerClient controllerClient;

    private final SqlSessionFactory sessionFactory;

    private final ControllerConfig config;

    private Role role;

    private final ConcurrentMap<Integer, BrokerNode> nodes;

    private final ScheduledExecutorService scheduledExecutorService;

    private final ExecutorService asyncExecutorService;

    /// The following fields are runtime specific
    private Lease lease;

    private final TopicManager topicManager;

    private final GroupManager groupManager;

    private final StreamManager streamManager;

    private DataStore dataStore;

    public DefaultMetadataStore(ControllerClient client, SqlSessionFactory sessionFactory, ControllerConfig config) {
        this.controllerClient = client;
        this.sessionFactory = sessionFactory;
        this.config = config;
        this.role = Role.Follower;
        this.nodes = new ConcurrentHashMap<>();
        this.scheduledExecutorService = new ScheduledThreadPoolExecutor(Runtime.getRuntime().availableProcessors(),
            new PrefixThreadFactory("Controller"));
        this.asyncExecutorService = Executors.newFixedThreadPool(10, new PrefixThreadFactory("Controller-Async"));
        this.topicManager = new TopicManager(this);
        this.groupManager = new GroupManager(this);
        this.streamManager = new StreamManager(this);
    }

    @Override
    public ControllerConfig config() {
        return config;
    }

    @Override
    public ExecutorService asyncExecutor() {
        return asyncExecutorService;
    }

    @Override
    public SqlSession openSession() {
        return sessionFactory.openSession(false);
    }

    @Override
    public SqlSessionFactory sessionFactory() {
        return sessionFactory;
    }

    @Override
    public ControllerClient controllerClient() {
        return controllerClient;
    }

    public DataStore getDataStore() {
        return dataStore;
    }

    @Override
    public void setDataStore(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    public void start() {
        LeaseTask leaseTask = new LeaseTask(this);
        leaseTask.run();
        this.scheduledExecutorService.scheduleAtFixedRate(leaseTask, 1,
            config.leaseLifeSpanInSecs() / 2, TimeUnit.SECONDS);
        this.scheduledExecutorService.scheduleWithFixedDelay(new ScanNodeTask(this), 1,
            config.scanIntervalInSecs(), TimeUnit.SECONDS);
        this.scheduledExecutorService.scheduleWithFixedDelay(new ScanYieldingQueueTask(this), 1,
            config.scanIntervalInSecs(), TimeUnit.SECONDS);
        this.scheduledExecutorService.scheduleWithFixedDelay(new SchedulerTask(this), 1,
            config.balanceWorkloadIntervalInSecs(), TimeUnit.SECONDS);
        this.scheduledExecutorService.scheduleAtFixedRate(new HeartbeatTask(this), 3,
            Math.max(config().nodeAliveIntervalInSecs() / 2, 10), TimeUnit.SECONDS);
        this.scheduledExecutorService.scheduleWithFixedDelay(new RecycleTopicTask(this), 1,
            config.deletedTopicLingersInSecs(), TimeUnit.SECONDS);
        this.scheduledExecutorService.scheduleWithFixedDelay(new RecycleGroupTask(this), 1,
            config.deletedGroupLingersInSecs(), TimeUnit.SECONDS);
        this.scheduledExecutorService.scheduleWithFixedDelay(new ReclaimS3ObjectTask(this), 1,
            config.scanIntervalInSecs(), TimeUnit.SECONDS);
        this.scheduledExecutorService.scheduleWithFixedDelay(new ScanTopicTask(this), 1,
            config.scanIntervalInSecs(), TimeUnit.SECONDS);
        this.scheduledExecutorService.scheduleWithFixedDelay(new ScanGroupTask(this), 1,
            config.scanIntervalInSecs(), TimeUnit.SECONDS);
        this.scheduledExecutorService.scheduleWithFixedDelay(new ScanAssignmentTask(this), 1,
            config.scanIntervalInSecs(), TimeUnit.SECONDS);
        this.scheduledExecutorService.scheduleWithFixedDelay(new ScanStreamTask(this), 1,
            config.scanIntervalInSecs(), TimeUnit.SECONDS);
        this.scheduledExecutorService.scheduleWithFixedDelay(new RecycleS3Task(this),
            config.recycleS3IntervalInSecs(), config.recycleS3IntervalInSecs(), TimeUnit.SECONDS);
        LOGGER.info("MetadataStore tasks scheduled");
    }

    private static Timestamp toTimestamp(Date time) {
        return Timestamp.newBuilder()
            .setSeconds(TimeUnit.MILLISECONDS.toSeconds(time.getTime()))
            .setNanos((int) TimeUnit.MILLISECONDS.toNanos(time.getTime() % 1000))
            .build();
    }

    @Override
    public CompletableFuture<Cluster> describeCluster(DescribeClusterRequest request) {
        if (isLeader()) {
            Cluster.Builder builder = Cluster.newBuilder()
                .setLease(apache.rocketmq.controller.v1.Lease.newBuilder()
                    .setEpoch(lease.getEpoch())
                    .setNodeId(lease.getNodeId())
                    .setExpirationTimestamp(toTimestamp(lease.getExpirationTime())).build());

            builder.setSummary(ClusterSummary.newBuilder()
                .setNodeQuantity(nodes.size())
                .setTopicQuantity(topicManager.topicQuantity())
                .setQueueQuantity(topicManager.queueQuantity())
                .setStreamQuantity(topicManager.streamQuantity())
                .setGroupQuantity(groupManager.getGroupCache().groupQuantity())
                .build());

            for (Map.Entry<Integer, BrokerNode> entry : nodes.entrySet()) {
                BrokerNode brokerNode = entry.getValue();
                apache.rocketmq.controller.v1.Node node = apache.rocketmq.controller.v1.Node.newBuilder()
                    .setId(entry.getKey())
                    .setName(brokerNode.getNode().getName())
                    .setLastHeartbeat(toTimestamp(brokerNode.lastKeepAliveTime(config)))
                    .setTopicNum(topicManager.topicNumOfNode(entry.getKey()))
                    .setQueueNum(topicManager.queueNumOfNode(entry.getKey()))
                    .setStreamNum(topicManager.streamNumOfNode(entry.getKey()))
                    .setGoingAway(brokerNode.isGoingAway())
                    .setAddress(brokerNode.getNode().getAddress())
                    .build();
                builder.addNodes(node);
            }
            return CompletableFuture.completedFuture(builder.build());
        } else {
            try {
                return controllerClient.describeCluster(this.leaderAddress(), request);
            } catch (ControllerException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    }

    @Override
    public CompletableFuture<Node> registerBrokerNode(String name, String address, String instanceId) {
        LOGGER.info("Register broker node with name={}, address={}, instance-id={}", name, address, instanceId);
        CompletableFuture<Node> future = new CompletableFuture<>();
        if (Strings.isNullOrEmpty(name)) {
            future.completeExceptionally(
                new ControllerException(Code.BAD_REQUEST_VALUE, "Broker name is null or empty"));
            return future;
        }

        if (Strings.isNullOrEmpty(address)) {
            future.completeExceptionally(
                new ControllerException(Code.BAD_REQUEST_VALUE, "Broker address is null or empty"));
            return future;
        }

        if (Strings.isNullOrEmpty(instanceId)) {
            future.completeExceptionally(
                new ControllerException(Code.BAD_REQUEST_VALUE, "Broker instance-id is null or empty"));
            return future;
        }

        return CompletableFuture.supplyAsync(() -> {
            for (; ; ) {
                if (this.isLeader()) {
                    try (SqlSession session = openSession()) {
                        NodeMapper nodeMapper = session.getMapper(NodeMapper.class);
                        Node node = nodeMapper.get(null, name, instanceId, null);
                        if (null != node) {
                            if (!Strings.isNullOrEmpty(address)) {
                                node.setAddress(address);
                            }
                            node.setEpoch(node.getEpoch() + 1);
                            nodeMapper.update(node);
                        } else {
                            LeaseMapper leaseMapper = session.getMapper(LeaseMapper.class);
                            Lease lease = leaseMapper.currentWithShareLock();
                            if (lease.getEpoch() != this.lease.getEpoch()) {
                                // Refresh cached lease
                                this.lease = lease;
                                LOGGER.info("Node[{}] has yielded its leader role", this.config.nodeId());
                                // Redirect register node to the new leader in the next iteration.
                                continue;
                            }
                            // epoch of node default to 1 when created
                            node = new Node();
                            node.setName(name);
                            node.setAddress(address);
                            node.setInstanceId(instanceId);
                            nodeMapper.create(node);
                            session.commit();
                        }
                        return node;
                    }
                } else {
                    try {
                        return controllerClient.registerBroker(this.leaderAddress(), name, address, instanceId).join();
                    } catch (ControllerException e) {
                        throw new CompletionException(e);
                    }
                }
            }
        }, this.asyncExecutorService);
    }

    @Override
    public void registerCurrentNode(String name, String address, String instanceId) throws ControllerException {
        try {
            Node node = registerBrokerNode(name, address, instanceId).get();
            config.setNodeId(node.getId());
            config.setEpoch(node.getEpoch());
        } catch (InterruptedException e) {
            throw new ControllerException(Code.INTERRUPTED_VALUE, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ControllerException) {
                throw (ControllerException) e.getCause();
            }
            throw new ControllerException(Code.INTERNAL_VALUE, e);
        }
    }

    @Override
    public void keepAlive(int nodeId, long epoch, boolean goingAway) {
        if (!isLeader()) {
            LOGGER.warn("Non-leader node cannot keep the node[node-id={}, epoch={}] alive", nodeId, epoch);
            return;
        }
        BrokerNode brokerNode = nodes.get(nodeId);
        if (null != brokerNode) {
            brokerNode.keepAlive(epoch, goingAway);
        }
    }

    @Override
    public void heartbeat() {
        if (isLeader()) {
            LOGGER.debug("Node of leader does not need to send heartbeat request");
        }

        try {
            String target = leaderAddress();
            controllerClient.heartbeat(target, config.nodeId(), config.epoch(), config.goingAway())
                .whenComplete((r, e) -> {
                    if (null != e) {
                        LOGGER.error("Failed to maintain heartbeat to {}", target);
                        return;
                    }
                    LOGGER.debug("Heartbeat to {} OK", target);
                });
        } catch (ControllerException e) {
            LOGGER.error("Failed to send heartbeat to leader node", e);
        }
    }

    public boolean maintainLeadershipWithSharedLock(SqlSession session) {
        LeaseMapper leaseMapper = session.getMapper(LeaseMapper.class);
        Lease current = leaseMapper.currentWithShareLock();
        if (current.getEpoch() != this.lease.getEpoch()) {
            // Current node is not leader any longer, forward to the new leader in the next iteration.
            this.lease = current;
            return false;
        }
        return true;
    }

    @Override
    public boolean isLeader() {
        return this.role == Role.Leader;
    }

    @Override
    public boolean hasAliveBrokerNodes() {
        return this.nodes.values().stream().anyMatch(node -> node.isAlive(config));
    }

    @Override
    public String leaderAddress() throws ControllerException {
        if (null == lease || lease.expired()) {
            LOGGER.error("No lease is populated yet or lease was expired");
            throw new ControllerException(Code.NO_LEADER_VALUE, "No leader node is available");
        }

        BrokerNode brokerNode = nodes.get(this.lease.getNodeId());
        if (null == brokerNode) {
            try (SqlSession session = openSession()) {
                NodeMapper nodeMapper = session.getMapper(NodeMapper.class);
                Node node = nodeMapper.get(this.lease.getNodeId(), null, null, null);
                if (null != node) {
                    addBrokerNode(node);
                    return node.getAddress();
                }
            }
            LOGGER.error("Address of broker[broker-id={}] is missing", this.lease.getNodeId());
            throw new ControllerException(Code.NOT_FOUND_VALUE,
                String.format("Node[node-id=%d] is missing", this.lease.getNodeId()));
        }

        return brokerNode.getNode().getAddress();
    }

    @Override
    public CompletableFuture<Long> createTopic(CreateTopicRequest request) {
        return topicManager.createTopic(request);
    }

    @Override
    public CompletableFuture<Void> deleteTopic(long topicId) {
        return topicManager.deleteTopic(topicId);
    }

    @Override
    public CompletableFuture<apache.rocketmq.controller.v1.Topic> describeTopic(Long topicId, String topicName) {
        return topicManager.describeTopic(topicId, topicName);
    }

    @Override
    public CompletableFuture<List<apache.rocketmq.controller.v1.Topic>> listTopics() {
        return topicManager.listTopics();
    }

    @Override
    public CompletableFuture<apache.rocketmq.controller.v1.Topic> updateTopic(UpdateTopicRequest request) {
        return topicManager.updateTopic(request);
    }

    @Override
    public CompletableFuture<Long> createGroup(CreateGroupRequest request) {
        return groupManager.createGroup(request);
    }

    @Override
    public CompletableFuture<ConsumerGroup> describeGroup(Long groupId, String groupName) {
        return groupManager.describeGroup(groupId, groupName);
    }

    @Override
    public CompletableFuture<Void> updateGroup(UpdateGroupRequest request) {
        return groupManager.updateGroup(request);
    }

    @Override
    public CompletableFuture<ConsumerGroup> deleteGroup(long groupId) {
        return groupManager.deleteGroup(groupId);
    }

    @Override
    public CompletableFuture<Collection<ConsumerGroup>> listGroups() {
        return groupManager.listGroups();
    }

    @Override
    public ConcurrentMap<Integer, BrokerNode> allNodes() {
        return nodes;
    }

    @Override
    public Optional<Integer> ownerNode(long topicId, int queueId) {
        return topicManager.ownerNode(topicId, queueId);
    }

    @Override
    public CompletableFuture<List<QueueAssignment>> listAssignments(Long topicId, Integer srcNodeId, Integer dstNodeId,
        AssignmentStatus status) {
        CompletableFuture<List<QueueAssignment>> future = new CompletableFuture<>();
        try (SqlSession session = openSession()) {
            QueueAssignmentMapper mapper = session.getMapper(QueueAssignmentMapper.class);
            List<QueueAssignment> queueAssignments = mapper.list(topicId, srcNodeId, dstNodeId, status, null);
            future.complete(queueAssignments);
            return future;
        }
    }

    @Override
    public CompletableFuture<Void> reassignMessageQueue(long topicId, int queueId, int dstNodeId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        for (; ; ) {
            if (isLeader()) {
                try (SqlSession session = sessionFactory.openSession()) {
                    if (!maintainLeadershipWithSharedLock(session)) {
                        continue;
                    }
                    QueueAssignmentMapper assignmentMapper = session.getMapper(QueueAssignmentMapper.class);
                    List<QueueAssignment> assignments = assignmentMapper.list(topicId, null, null, null, null);
                    for (QueueAssignment assignment : assignments) {
                        if (assignment.getQueueId() != queueId) {
                            continue;
                        }

                        switch (assignment.getStatus()) {
                            case ASSIGNMENT_STATUS_YIELDING -> {
                                assignment.setDstNodeId(dstNodeId);
                                assignmentMapper.update(assignment);
                            }
                            case ASSIGNMENT_STATUS_ASSIGNED -> {
                                assignment.setDstNodeId(dstNodeId);
                                assignment.setStatus(AssignmentStatus.ASSIGNMENT_STATUS_YIELDING);
                                assignmentMapper.update(assignment);
                            }
                            case ASSIGNMENT_STATUS_DELETED ->
                                future.completeExceptionally(new ControllerException(Code.NOT_FOUND_VALUE, "Already deleted"));
                        }
                        break;
                    }
                    session.commit();
                }
                future.complete(null);
                break;
            } else {
                try {
                    this.controllerClient.reassignMessageQueue(leaderAddress(), topicId, queueId, dstNodeId).whenComplete((res, e) -> {
                        if (e != null) {
                            future.completeExceptionally(e);
                        } else {
                            future.complete(null);
                        }
                    });
                } catch (ControllerException e) {
                    future.completeExceptionally(e);
                }
            }
        }
        return future;
    }

    @Override
    public CompletableFuture<Void> markMessageQueueAssignable(long topicId, int queueId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        for (; ; ) {
            if (isLeader()) {
                try (SqlSession session = openSession()) {
                    QueueAssignmentMapper assignmentMapper = session.getMapper(QueueAssignmentMapper.class);
                    List<QueueAssignment> assignments = assignmentMapper.list(topicId, null, null, AssignmentStatus.ASSIGNMENT_STATUS_YIELDING, null);
                    for (QueueAssignment assignment : assignments) {
                        if (assignment.getQueueId() != queueId) {
                            continue;
                        }
                        assignment.setStatus(AssignmentStatus.ASSIGNMENT_STATUS_ASSIGNED);
                        assignmentMapper.update(assignment);
                        LOGGER.info("Mark queue[topic-id={}, queue-id={}] assignable", topicId, queueId);
                        break;
                    }
                    session.commit();
                }
                future.complete(null);
                break;
            } else {
                try {
                    this.controllerClient.notifyQueueClose(leaderAddress(), topicId, queueId).whenComplete((res, e) -> {
                        if (null != e) {
                            future.completeExceptionally(e);
                        } else {
                            future.complete(null);
                        }
                    });
                } catch (ControllerException e) {
                    future.completeExceptionally(e);
                }
            }
        }
        return future;
    }

    @Override
    public CompletableFuture<Void> commitOffset(long groupId, long topicId, int queueId, long offset) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        for (; ; ) {
            if (isLeader()) {
                try (SqlSession session = openSession()) {
                    if (!maintainLeadershipWithSharedLock(session)) {
                        continue;
                    }
                    GroupProgressMapper groupProgressMapper = session.getMapper(GroupProgressMapper.class);
                    GroupProgress progress = new GroupProgress();
                    progress.setGroupId(groupId);
                    progress.setTopicId(topicId);
                    progress.setQueueId(queueId);
                    progress.setQueueOffset(offset);
                    groupProgressMapper.createOrUpdate(progress);
                    session.commit();
                }
                future.complete(null);
            } else {
                try {
                    this.controllerClient.commitOffset(leaderAddress(), groupId, topicId, queueId, offset).whenComplete((res, e) -> {
                        if (null != e) {
                            future.completeExceptionally(e);
                        } else {
                            future.complete(null);
                        }
                    });
                } catch (ControllerException e) {
                    future.completeExceptionally(e);
                }
            }
            break;
        }
        return future;
    }

    @Override
    public CompletableFuture<Long> getConsumerOffset(long consumerGroupId, long topicId, int queueId) {
        try (SqlSession session = openSession()) {
            GroupProgressMapper groupProgressMapper = session.getMapper(GroupProgressMapper.class);
            GroupProgress progress = groupProgressMapper.get(consumerGroupId, topicId, queueId);
            if (Objects.isNull(progress)) {
                return CompletableFuture.completedFuture(0L);
            } else {
                return CompletableFuture.completedFuture(progress.getQueueOffset());
            }
        }
    }

    @Override
    public CompletableFuture<StreamMetadata> getStream(long topicId, int queueId, Long groupId, StreamRole streamRole) {
        return CompletableFuture.supplyAsync(() -> {
            try (SqlSession session = openSession()) {
                StreamMapper streamMapper = session.getMapper(StreamMapper.class);
                StreamCriteria criteria = StreamCriteria.newBuilder()
                    .withTopicId(topicId)
                    .withQueueId(queueId)
                    .withGroupId(groupId)
                    .build();
                List<Stream> streams = streamMapper.byCriteria(criteria)
                    .stream()
                    .filter(stream -> stream.getStreamRole() == streamRole).toList();
                if (streams.isEmpty()) {
                    if (streamRole == StreamRole.STREAM_ROLE_RETRY) {
                        QueueAssignmentMapper assignmentMapper = session.getMapper(QueueAssignmentMapper.class);
                        List<QueueAssignment> assignments = assignmentMapper
                            .list(topicId, null, null, null, null)
                            .stream().filter(assignment -> assignment.getQueueId() == queueId)
                            .toList();

                        // Verify assignment and queue are OK
                        if (assignments.isEmpty()) {
                            String msg = String.format("Queue assignment for topic-id=%d queue-id=%d is not found",
                                topicId, queueId);
                            throw new CompletionException(new ControllerException(Code.NOT_FOUND_VALUE, msg));
                        }

                        if (assignments.size() != 1) {
                            String msg = String.format("%d queue assignments for topic-id=%d queue-id=%d is found",
                                assignments.size(), topicId, queueId);
                            throw new CompletionException(new ControllerException(Code.ILLEGAL_STATE_VALUE, msg));
                        }
                        QueueAssignment assignment = assignments.get(0);
                        switch (assignment.getStatus()) {
                            case ASSIGNMENT_STATUS_YIELDING -> {
                                String msg = String.format("Queue[topic-id=%d queue-id=%d] is under migration. " +
                                    "Please create retry stream later", topicId, queueId);
                                throw new CompletionException(new ControllerException(Code.ILLEGAL_STATE_VALUE, msg));
                            }
                            case ASSIGNMENT_STATUS_DELETED -> {
                                String msg = String.format("Queue[topic-id=%d queue-id=%d] has been deleted",
                                    topicId, queueId);
                                throw new CompletionException(new ControllerException(Code.ILLEGAL_STATE_VALUE, msg));
                            }
                            case ASSIGNMENT_STATUS_ASSIGNED -> {
                                // OK
                            }
                            default -> {
                                String msg = String.format("Status of Queue[topic-id=%d queue-id=%d] is unsupported",
                                    topicId, queueId);
                                throw new CompletionException(new ControllerException(Code.INTERNAL_VALUE, msg));
                            }
                        }

                        // Verify Group exists.
                        GroupMapper groupMapper = session.getMapper(GroupMapper.class);
                        List<Group> groups = groupMapper.byCriteria(GroupCriteria.newBuilder()
                            .setGroupId(groupId)
                            .setStatus(GroupStatus.GROUP_STATUS_ACTIVE)
                            .build());
                        if (groups.size() != 1) {
                            String msg = String.format("Group[group-id=%d] is not found", groupId);
                            throw new CompletionException(new ControllerException(Code.NOT_FOUND_VALUE, msg));
                        }

                        int nodeId = assignment.getDstNodeId();
                        long streamId = topicManager.createStream(streamMapper, topicId, queueId, groupId, streamRole, nodeId);
                        session.commit();

                        Stream stream = streamMapper.getByStreamId(streamId);
                        return StreamMetadata.newBuilder()
                            .setStreamId(streamId)
                            .setEpoch(stream.getEpoch())
                            .setRangeId(stream.getRangeId())
                            .setStartOffset(stream.getStartOffset())
                            // Stream is uninitialized, its end offset is definitely 0.
                            .setEndOffset(0)
                            .setState(stream.getState())
                            .build();
                    }

                    // For other types of streams, creation is explicit.
                    ControllerException e = new ControllerException(Code.NOT_FOUND_VALUE,
                        String.format("Stream for topic-id=%d, queue-id=%d, stream-role=%s is not found", topicId, queueId, streamRole.name()));
                    throw new CompletionException(e);
                } else {
                    Stream stream = streams.get(0);
                    long endOffset = 0;
                    switch (stream.getState()) {
                        case UNINITIALIZED -> {
                        }
                        case DELETED -> {
                            ControllerException e = new ControllerException(Code.NOT_FOUND_VALUE,
                                String.format("Stream for topic-id=%d, queue-id=%d, stream-role=%s has been deleted",
                                    topicId, queueId, streamRole.name()));
                            throw new CompletionException(e);
                        }
                        case CLOSING, OPEN, CLOSED -> {
                            RangeMapper rangeMapper = session.getMapper(RangeMapper.class);
                            Range range = rangeMapper.get(stream.getRangeId(), stream.getId(), null);
                            if (null == range) {
                                LOGGER.error("Expected range[range-id={}] of stream[topic-id={}, queue-id={}, " +
                                        "stream-id={}, stream-state={}, role={}] is NOT found", stream.getRangeId(),
                                    stream.getTopicId(), stream.getQueueId(), stream.getId(), stream.getState(),
                                    stream.getStreamRole());
                            }
                            assert null != range;
                            endOffset = range.getEndOffset();
                        }
                    }
                    return StreamMetadata.newBuilder()
                        .setStreamId(stream.getId())
                        .setEpoch(stream.getEpoch())
                        .setRangeId(stream.getRangeId())
                        .setStartOffset(stream.getStartOffset())
                        .setEndOffset(endOffset)
                        .setState(stream.getState())
                        .build();
                }
            }
        }, asyncExecutorService);
    }

    @Override
    public CompletableFuture<Void> onQueueClosed(long topicId, int queueId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        for (; ; ) {
            if (isLeader()) {
                try (SqlSession session = openSession()) {
                    if (!maintainLeadershipWithSharedLock(session)) {
                        continue;
                    }
                    QueueAssignmentMapper assignmentMapper = session.getMapper(QueueAssignmentMapper.class);
                    QueueAssignment assignment = assignmentMapper.get(topicId, queueId);
                    assignment.setStatus(AssignmentStatus.ASSIGNMENT_STATUS_ASSIGNED);
                    assignmentMapper.update(assignment);

                    StreamMapper streamMapper = session.getMapper(StreamMapper.class);
                    StreamCriteria criteria = StreamCriteria.newBuilder()
                        .withTopicId(topicId)
                        .withQueueId(queueId)
                        .withState(StreamState.CLOSING)
                        .build();
                    streamMapper.updateStreamState(criteria, StreamState.CLOSED);
                    session.commit();
                    LOGGER.info("Update status of queue assignment and stream since all its belonging streams are closed," +
                        " having topic-id={}, queue-id={}", topicId, queueId);
                    future.complete(null);
                    return future;
                }
            } else {
                try {
                    controllerClient.notifyQueueClose(leaderAddress(), topicId, queueId)
                        .whenComplete((res, e) -> {
                            if (null != e) {
                                future.completeExceptionally(e);
                            }
                            future.complete(null);
                        });
                } catch (ControllerException e) {
                    future.completeExceptionally(e);
                }
                return future;
            }
        }
    }

    @Override
    public CompletableFuture<StreamMetadata> openStream(long streamId, long epoch, int nodeId) {
        CompletableFuture<StreamMetadata> future = new CompletableFuture<>();
        for (; ; ) {
            if (isLeader()) {
                try (SqlSession session = openSession()) {
                    if (!maintainLeadershipWithSharedLock(session)) {
                        continue;
                    }
                    StreamMapper streamMapper = session.getMapper(StreamMapper.class);
                    RangeMapper rangeMapper = session.getMapper(RangeMapper.class);
                    Stream stream = streamMapper.getByStreamId(streamId);
                    // Verify target stream exists
                    if (null == stream || stream.getState() == StreamState.DELETED) {
                        ControllerException e = new ControllerException(Code.NOT_FOUND_VALUE,
                            String.format("Stream[stream-id=%d] is not found", streamId)
                        );
                        future.completeExceptionally(e);
                        return future;
                    }

                    // Verify stream owner is correct
                    switch (stream.getState()) {
                        case CLOSING -> {
                            // nodeId should be equal to stream.srcNodeId
                            if (nodeId != stream.getSrcNodeId()) {
                                LOGGER.warn("State of Stream[stream-id={}] is {}. Current owner should be {}, while {} " +
                                        "is attempting to open. Fenced!", stream.getId(), stream.getState(), stream.getSrcNodeId(),
                                    nodeId);
                                ControllerException e = new ControllerException(Code.FENCED_VALUE, "Node does not match");
                                future.completeExceptionally(e);
                                return future;
                            }
                        }
                        case OPEN, CLOSED, UNINITIALIZED -> {
                            // nodeId should be equal to stream.dstNodeId
                            if (nodeId != stream.getDstNodeId()) {
                                LOGGER.warn("State of Stream[stream-id={}] is {}. Its current owner is {}, {} is attempting to open. Fenced!",
                                    streamId, stream.getState(), stream.getDstNodeId(), nodeId);
                                ControllerException e = new ControllerException(Code.FENCED_VALUE, "Node does not match");
                                future.completeExceptionally(e);
                                return future;
                            }
                        }
                    }

                    // Verify epoch
                    if (epoch != stream.getEpoch()) {
                        LOGGER.warn("Epoch of Stream[stream-id={}] is {}, while the open stream request epoch is {}",
                            streamId, stream.getEpoch(), epoch);
                        ControllerException e = new ControllerException(Code.FENCED_VALUE, "Epoch of stream is deprecated");
                        future.completeExceptionally(e);
                        return future;
                    }

                    // Verify that current stream state allows open ops
                    switch (stream.getState()) {
                        case CLOSING, OPEN -> {
                            LOGGER.warn("Stream[stream-id={}] is already OPEN with epoch={}", streamId, stream.getEpoch());
                            Range range = rangeMapper.get(stream.getRangeId(), streamId, null);
                            StreamMetadata metadata = StreamMetadata.newBuilder()
                                .setStreamId(streamId)
                                .setEpoch(epoch)
                                .setRangeId(stream.getRangeId())
                                .setStartOffset(stream.getStartOffset())
                                .setEndOffset(range.getEndOffset())
                                .setState(stream.getState())
                                .build();
                            future.complete(metadata);
                            return future;
                        }
                        case UNINITIALIZED, CLOSED -> {
                        }
                        default -> {
                            String msg = String.format("State of Stream[stream-id=%d] is %s, which is not supported",
                                stream.getId(), stream.getState());
                            future.completeExceptionally(new ControllerException(Code.ILLEGAL_STATE_VALUE, msg));
                            return future;
                        }
                    }

                    // Now that the request is valid, update the stream's epoch and create a new range for this broker

                    // If stream.state == uninitialized, its stream.rangeId will be -1;
                    // If stream.state == closed, stream.rangeId will be the previous one;

                    // get new range's start offset
                    long startOffset;
                    if (StreamState.UNINITIALIZED == stream.getState()) {
                        // default regard this range is the first range in stream, use 0 as start offset
                        startOffset = 0;
                    } else {
                        assert StreamState.CLOSED == stream.getState();
                        Range prevRange = rangeMapper.get(stream.getRangeId(), streamId, null);
                        // if stream is closed, use previous range's end offset as start offset
                        startOffset = prevRange.getEndOffset();
                    }

                    // Increase stream epoch
                    stream.setEpoch(epoch + 1);
                    // Increase range-id
                    stream.setRangeId(stream.getRangeId() + 1);
                    stream.setStartOffset(stream.getStartOffset());
                    stream.setState(StreamState.OPEN);
                    streamMapper.update(stream);

                    // Create a new range for the stream
                    Range range = new Range();
                    range.setStreamId(streamId);
                    range.setNodeId(stream.getDstNodeId());
                    range.setStartOffset(startOffset);
                    range.setEndOffset(startOffset);
                    range.setEpoch(epoch + 1);
                    range.setRangeId(stream.getRangeId());
                    rangeMapper.create(range);
                    LOGGER.info("Node[node-id={}] opens stream [stream-id={}] with epoch={}",
                        this.lease.getNodeId(), streamId, epoch + 1);
                    // Commit transaction
                    session.commit();

                    // Build open stream response
                    StreamMetadata metadata = StreamMetadata.newBuilder()
                        .setStreamId(streamId)
                        .setEpoch(epoch + 1)
                        .setRangeId(stream.getRangeId())
                        .setStartOffset(stream.getStartOffset())
                        .setEndOffset(range.getEndOffset())
                        .setState(StreamState.OPEN)
                        .build();
                    future.complete(metadata);
                    return future;
                } catch (Throwable e) {
                    LOGGER.error("Unexpected exception raised while open stream", e);
                    future.completeExceptionally(e);
                    return future;
                }
            } else {
                OpenStreamRequest request = OpenStreamRequest.newBuilder()
                    .setStreamId(streamId)
                    .setStreamEpoch(epoch)
                    .build();
                try {
                    return this.controllerClient.openStream(this.leaderAddress(), request)
                        .thenApply(OpenStreamReply::getStreamMetadata);
                } catch (ControllerException e) {
                    future.completeExceptionally(e);
                }
            }
        }
    }

    @Override
    public CompletableFuture<Void> closeStream(long streamId, long streamEpoch, int nodeId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        for (; ; ) {
            if (isLeader()) {
                try (SqlSession session = openSession()) {
                    if (!maintainLeadershipWithSharedLock(session)) {
                        continue;
                    }
                    StreamMapper streamMapper = session.getMapper(StreamMapper.class);

                    Stream stream = streamMapper.getByStreamId(streamId);

                    // Verify resource existence
                    if (null == stream) {
                        ControllerException e = new ControllerException(Code.NOT_FOUND_VALUE,
                            String.format("Stream[stream-id=%d] is not found", streamId));
                        future.completeExceptionally(e);
                        break;
                    }

                    // Verify stream owner
                    switch (stream.getState()) {
                        case CLOSING -> {
                            if (nodeId != stream.getSrcNodeId()) {
                                LOGGER.warn("State of Stream[stream-id={}] is {}, stream.srcNodeId={} while close stream request from Node[node-id={}]. Fenced",
                                    streamId, stream.getState(), stream.getDstNodeId(), nodeId);
                                ControllerException e = new ControllerException(Code.FENCED_VALUE,
                                    "Close stream op is fenced by non-owner");
                                future.completeExceptionally(e);
                            }
                        }
                        case OPEN -> {
                            if (nodeId != stream.getDstNodeId()) {
                                LOGGER.warn("dst-node-id of stream {} is {}, fencing close stream request from Node[node-id={}]",
                                    streamId, stream.getDstNodeId(), nodeId);
                                ControllerException e = new ControllerException(Code.FENCED_VALUE,
                                    "Close stream op is fenced by non-owner");
                                future.completeExceptionally(e);
                            }
                        }
                    }

                    // Verify epoch
                    if (streamEpoch != stream.getEpoch()) {
                        ControllerException e = new ControllerException(Code.FENCED_VALUE, "Stream epoch is deprecated");
                        future.completeExceptionally(e);
                        break;
                    }

                    // Make closeStream reentrant
                    if (stream.getState() == StreamState.CLOSED) {
                        future.complete(null);
                        break;
                    }

                    // Flag state as closed
                    stream.setState(StreamState.CLOSED);
                    streamMapper.update(stream);
                    session.commit();
                    future.complete(null);
                    break;
                }
            } else {
                CloseStreamRequest request = CloseStreamRequest.newBuilder()
                    .setStreamId(streamId)
                    .setStreamEpoch(streamEpoch)
                    .build();
                try {
                    this.controllerClient.closeStream(this.leaderAddress(), request).whenComplete(((reply, e) -> {
                        if (null != e) {
                            future.completeExceptionally(e);
                        } else {
                            future.complete(null);
                        }
                    }));
                } catch (ControllerException e) {
                    future.completeExceptionally(e);
                }
                break;
            }
        }
        return future;
    }

    @Override
    public CompletableFuture<List<StreamMetadata>> listOpenStreams(int nodeId) {
        CompletableFuture<List<StreamMetadata>> future = new CompletableFuture<>();
        for (; ; ) {
            if (isLeader()) {
                try (SqlSession session = openSession()) {
                    if (!maintainLeadershipWithSharedLock(session)) {
                        continue;
                    }
                    StreamMapper streamMapper = session.getMapper(StreamMapper.class);
                    StreamCriteria criteria = StreamCriteria.newBuilder()
                        .withDstNodeId(nodeId)
                        .withState(StreamState.OPEN)
                        .build();
                    List<StreamMetadata> streams = buildStreamMetadata(streamMapper.byCriteria(criteria), session);
                    future.complete(streams);
                    break;
                }
            } else {
                ListOpenStreamsRequest request = ListOpenStreamsRequest.newBuilder()
                    .setBrokerId(nodeId)
                    .build();
                try {
                    return controllerClient.listOpenStreams(leaderAddress(), request)
                        .thenApply((ListOpenStreamsReply::getStreamMetadataList));
                } catch (ControllerException e) {
                    future.completeExceptionally(e);
                }
                break;
            }
        }
        return future;
    }

    private List<StreamMetadata> buildStreamMetadata(List<Stream> streams, SqlSession session) {
        RangeMapper rangeMapper = session.getMapper(RangeMapper.class);
        return streams.stream()
            .map(stream -> {
                int rangeId = stream.getRangeId();
                Range range = rangeMapper.get(rangeId, stream.getId(), null);
                return StreamMetadata.newBuilder()
                    .setStreamId(stream.getId())
                    .setStartOffset(stream.getStartOffset())
                    .setEndOffset(null == range ? 0 : range.getEndOffset())
                    .setEpoch(stream.getEpoch())
                    .setState(stream.getState())
                    .setRangeId(stream.getRangeId())
                    .build();
            })
            .toList();
    }

    @Override
    public CompletableFuture<List<StreamMetadata>> getStreams(List<Long> streamIds) {
        if (null == streamIds || streamIds.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        return CompletableFuture.supplyAsync(() -> {
            try (SqlSession session = openSession()) {
                StreamMapper streamMapper = session.getMapper(StreamMapper.class);
                StreamCriteria criteria = StreamCriteria.newBuilder().addBatchStreamIds(streamIds).build();
                List<Stream> streams = streamMapper.byCriteria(criteria);
                return buildStreamMetadata(streams, session);
            }
        }, asyncExecutorService);
    }

    @Override
    public TerminationStage fireClose() {
        config.flagGoingAway();
        if (isLeader()) {
            // TODO: yield leadership
            return TerminationStage.TS_TRANSFERRING_LEADERSHIP;
        } else {
            // Notify leader that this node is going away shortly
            heartbeat();
            return TerminationStage.TS_TRANSFERRING_STREAM;
        }
    }

    @Override
    public void close() throws IOException {
        this.scheduledExecutorService.shutdown();
        this.asyncExecutorService.shutdown();
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public void setLease(Lease lease) {
        this.lease = lease;
    }

    public Lease getLease() {
        return lease;
    }

    public void addBrokerNode(Node node) {
        this.nodes.put(node.getId(), new BrokerNode(node));
    }

    public ConcurrentMap<Integer, BrokerNode> getNodes() {
        return nodes;
    }

    public ControllerConfig getConfig() {
        return config;
    }

    @Override
    public CompletableFuture<String> addressOfNode(int nodeId) {
        BrokerNode node = this.nodes.get(nodeId);
        if (null != node) {
            return CompletableFuture.completedFuture(node.getNode().getAddress());
        }

        return CompletableFuture.supplyAsync(() -> {
            try (SqlSession session = openSession()) {
                NodeMapper mapper = session.getMapper(NodeMapper.class);
                Node n = mapper.get(nodeId, null, null, null);
                if (null != n) {
                    return n.getAddress();
                }
            }
            return null;
        }, asyncExecutorService);
    }

    @Override
    public void applyTopicChange(List<Topic> topics) {
        topicManager.getTopicCache().apply(topics);
    }

    @Override
    public void applyAssignmentChange(List<QueueAssignment> assignments) {
        topicManager.getAssignmentCache().apply(assignments);
    }

    @Override
    public void applyGroupChange(List<Group> groups) {
        this.groupManager.getGroupCache().apply(groups);
    }

    @Override
    public void applyStreamChange(List<Stream> streams) {
        this.topicManager.getStreamCache().apply(streams);

        // delete associated S3 assets
        if (isLeader()) {
            for (Stream stream : streams) {
                if (stream.getState() == StreamState.DELETED) {
                    try {
                        LOGGER.info("Delete associated S3 resources for stream[stream-id={}]", stream.getId());
                        streamManager.deleteStream(stream.getId());
                        LOGGER.info("Associated S3 resources deleted for stream[stream-id={}]", stream.getId());
                    } catch (Throwable e) {
                        LOGGER.error("Unexpected exception raised while cleaning S3 resources on stream deletion", e);
                    }
                }
            }
        }

    }
}