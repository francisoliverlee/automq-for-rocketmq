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

package com.automq.rocketmq.store;

import com.automq.rocketmq.common.config.StoreConfig;
import com.automq.rocketmq.common.model.generated.FlatMessage;
import com.automq.rocketmq.metadata.api.StoreMetadataService;
import com.automq.rocketmq.store.api.LogicQueue;
import com.automq.rocketmq.store.api.LogicQueueManager;
import com.automq.rocketmq.store.api.MessageStore;
import com.automq.rocketmq.store.api.S3ObjectOperator;
import com.automq.rocketmq.store.api.StreamStore;
import com.automq.rocketmq.store.exception.StoreException;
import com.automq.rocketmq.store.model.generated.ReceiptHandle;
import com.automq.rocketmq.store.model.generated.TimerHandlerType;
import com.automq.rocketmq.store.model.message.AckResult;
import com.automq.rocketmq.store.model.message.ChangeInvisibleDurationResult;
import com.automq.rocketmq.store.model.message.ClearRetryMessagesResult;
import com.automq.rocketmq.store.model.message.Filter;
import com.automq.rocketmq.store.model.message.PopResult;
import com.automq.rocketmq.store.model.message.PullResult;
import com.automq.rocketmq.store.model.message.PutResult;
import com.automq.rocketmq.store.model.message.ResetConsumeOffsetResult;
import com.automq.rocketmq.store.service.InflightService;
import com.automq.rocketmq.store.service.ReviveService;
import com.automq.rocketmq.store.service.SnapshotService;
import com.automq.rocketmq.store.service.TimerService;
import com.automq.rocketmq.store.service.api.KVService;
import com.automq.rocketmq.store.util.FlatMessageUtil;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.automq.rocketmq.store.util.SerializeUtil.decodeReceiptHandle;

public class MessageStoreImpl implements MessageStore {
    public static final String KV_NAMESPACE_CHECK_POINT = "check_point";
    public static final String KV_NAMESPACE_FIFO_INDEX = "fifo_index";

    private final AtomicBoolean started = new AtomicBoolean(false);

    StoreConfig config;

    private final StreamStore streamStore;
    private final StoreMetadataService metadataService;
    private final KVService kvService;
    private final TimerService timerService;
    private final ReviveService reviveService;
    private final InflightService inflightService;
    private final SnapshotService snapshotService;
    private final LogicQueueManager logicQueueManager;
    private final S3ObjectOperator s3ObjectOperator;

    public MessageStoreImpl(StoreConfig config, StreamStore streamStore,
        StoreMetadataService metadataService, KVService kvService, TimerService timerService,
        InflightService inflightService, SnapshotService snapshotService, LogicQueueManager logicQueueManager,
        ReviveService reviveService, S3ObjectOperator s3ObjectOperator) {
        this.config = config;
        this.streamStore = streamStore;
        this.metadataService = metadataService;
        this.kvService = kvService;
        this.timerService = timerService;
        this.inflightService = inflightService;
        this.snapshotService = snapshotService;
        this.logicQueueManager = logicQueueManager;
        this.reviveService = reviveService;
        this.s3ObjectOperator = s3ObjectOperator;
    }

    @Override
    public LogicQueueManager getTopicQueueManager() {
        return logicQueueManager;
    }

    public StreamStore streamStore() {
        return streamStore;
    }

    /**
     * @return {@link S3ObjectOperator} instance
     */
    @Override
    public S3ObjectOperator getS3ObjectOperator() {
        return s3ObjectOperator;
    }

    @Override
    public void start() throws Exception {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        clearStateMachineData();
        streamStore.start();
        timerService.registerHandler(TimerHandlerType.TIMER_MESSAGE, timerTag -> put(FlatMessage.getRootAsFlatMessage(timerTag.payloadAsByteBuffer())));
        timerService.start();
        snapshotService.start();
        logicQueueManager.start();
    }

    @Override
    public void shutdown() throws Exception {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        logicQueueManager.shutdown();
        snapshotService.shutdown();
        timerService.start();
        streamStore.shutdown();
        clearStateMachineData();
    }

    private void clearStateMachineData() throws StoreException {
        // clear all statemachine related data in rocksdb
        kvService.clear(KV_NAMESPACE_CHECK_POINT);
        kvService.clear(KV_NAMESPACE_FIFO_INDEX);
        timerService.clear();
    }

    @Override
    public CompletableFuture<PopResult> pop(long consumerGroupId, long topicId, int queueId, Filter filter,
        int batchSize, boolean fifo, boolean retry, long invisibleDuration) {
        if (fifo && retry) {
            return CompletableFuture.failedFuture(new RuntimeException("Fifo and retry cannot be true at the same time"));
        }
        return logicQueueManager.getOrCreate(topicId, queueId)
            .thenCompose(topicQueue -> {
                if (fifo) {
                    return topicQueue.popFifo(consumerGroupId, filter, batchSize, invisibleDuration);
                }
                if (retry) {
                    return topicQueue.popRetry(consumerGroupId, filter, batchSize, invisibleDuration);
                }
                return topicQueue.popNormal(consumerGroupId, filter, batchSize, invisibleDuration);
            });
    }

    @Override
    public CompletableFuture<PullResult> pull(long consumerGroupId, long topicId, int queueId, Filter filter,
        long offset, int batchSize, boolean retry) {
        return logicQueueManager.getOrCreate(topicId, queueId)
            .thenCompose(topicQueue -> {
                if (retry) {
                    return topicQueue.pullRetry(consumerGroupId, filter, offset, batchSize);
                }
                return topicQueue.pullNormal(consumerGroupId, filter, offset, batchSize);
            });
    }

    @Override
    public CompletableFuture<PutResult> put(FlatMessage message) {
        long deliveryTimestamp = message.systemProperties().deliveryTimestamp();
        if (deliveryTimestamp > 0 && deliveryTimestamp - System.currentTimeMillis() > 1000) {
            try {
                timerService.enqueue(deliveryTimestamp, TimerHandlerType.TIMER_MESSAGE, FlatMessageUtil.flatBufferToByteArray(message));
                return CompletableFuture.completedFuture(new PutResult(PutResult.Status.PUT_DELAYED, -1));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        return logicQueueManager.getOrCreate(message.topicId(), message.queueId())
            .thenCompose(topicQueue -> topicQueue.put(message));
    }

    @Override
    public CompletableFuture<AckResult> ack(String receiptHandle) {
        // Write ack operation to operation log.
        // Operation id should be monotonically increasing for each queue
        ReceiptHandle handle = decodeReceiptHandle(receiptHandle);
        return logicQueueManager.getOrCreate(handle.topicId(), handle.queueId())
            .thenCompose(topicQueue -> topicQueue.ack(receiptHandle));
    }

    @Override
    public CompletableFuture<ChangeInvisibleDurationResult> changeInvisibleDuration(String receiptHandle,
        long invisibleDuration) {
        // Write change invisible duration operation to operation log.
        // Operation id should be monotonically increasing for each queue
        ReceiptHandle handle = decodeReceiptHandle(receiptHandle);
        return logicQueueManager.getOrCreate(handle.topicId(), handle.queueId())
            .thenCompose(topicQueue -> topicQueue.changeInvisibleDuration(receiptHandle, invisibleDuration));
    }

    @Override
    public CompletableFuture<Void> closeQueue(long topicId, int queueId) {
        return logicQueueManager.close(topicId, queueId);
    }

    @Override
    public CompletableFuture<Integer> getInflightStats(long consumerGroupId, long topicId, int queueId) {
        // Get check point count of specified consumer, topic and queue.
        return logicQueueManager.getOrCreate(topicId, queueId)
            .thenApply(topicQueue -> topicQueue.getInflightStats(consumerGroupId));
    }

    @Override
    public CompletableFuture<LogicQueue.QueueOffsetRange> getOffsetRange(long topicId, int queueId) {
        return logicQueueManager.getOrCreate(topicId, queueId)
            .thenCompose(LogicQueue::getOffsetRange);
    }

    @Override
    public CompletableFuture<Long> getConsumeOffset(long consumerGroupId, long topicId, int queueId) {
        return logicQueueManager.getOrCreate(topicId, queueId)
            .thenApply(topicQueue -> topicQueue.getConsumeOffset(consumerGroupId));
    }

    @Override
    public CompletableFuture<ResetConsumeOffsetResult> resetConsumeOffset(long consumerGroupId, long topicId,
        int queueId, long offset) {
        return logicQueueManager.getOrCreate(topicId, queueId)
            .thenCompose(topicQueue -> topicQueue.resetConsumeOffset(consumerGroupId, offset));
    }

    @Override
    public CompletableFuture<ClearRetryMessagesResult> clearRetryMessages(long consumerGroupId, long topicId,
        int queueId) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
