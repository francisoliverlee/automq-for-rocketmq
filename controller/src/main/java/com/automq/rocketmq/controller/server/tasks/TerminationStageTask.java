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

package com.automq.rocketmq.controller.server.tasks;

import apache.rocketmq.controller.v1.AssignmentStatus;
import apache.rocketmq.controller.v1.Code;
import apache.rocketmq.controller.v1.Status;
import apache.rocketmq.controller.v1.TerminateNodeReply;
import apache.rocketmq.controller.v1.TerminationStage;
import com.automq.rocketmq.controller.MetadataStore;
import com.automq.rocketmq.metadata.mapper.QueueAssignmentMapper;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TerminationStageTask implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(TerminationStageTask.class);

    private final MetadataStore metadataStore;
    private final ScheduledExecutorService executorService;
    private final Context context;
    private final StreamObserver<TerminateNodeReply> observer;

    public TerminationStageTask(MetadataStore metadataStore, ScheduledExecutorService executorService, Context context,
        StreamObserver<TerminateNodeReply> observer) {
        this.metadataStore = metadataStore;
        this.executorService = executorService;
        this.context = context;
        this.observer = observer;
    }

    @Override
    public void run() {
        try (SqlSession session = metadataStore.openSession()) {
            if (context.isCancelled()) {
                return;
            }

            if (metadataStore.isLeader()) {
                LOGGER.info("Still transferring leadership");
                TerminateNodeReply reply = TerminateNodeReply.newBuilder()
                    .setStatus(Status.newBuilder().setCode(Code.OK).build())
                    .setStage(TerminationStage.TS_TRANSFERRING_LEADERSHIP)
                    .build();
                observer.onNext(reply);
            } else {
                QueueAssignmentMapper assignmentMapper = session.getMapper(QueueAssignmentMapper.class);
                int assigned = assignmentMapper.list(null, null, metadataStore.config().nodeId(),
                    AssignmentStatus.ASSIGNMENT_STATUS_ASSIGNED, null).size();

                int yielding = assignmentMapper.list(null, metadataStore.config().nodeId(), null,
                    AssignmentStatus.ASSIGNMENT_STATUS_YIELDING, null).size();

                if (assigned > 0) {
                    LOGGER.info("There are {} queues assigned ", assigned);
                    TerminateNodeReply reply = TerminateNodeReply.newBuilder()
                        .setStatus(Status.newBuilder().setCode(Code.OK).build())
                        .setStage(TerminationStage.TS_TRANSFERRING_STREAM)
                        .build();
                    observer.onNext(reply);
                } else if (yielding > 0) {
                    LOGGER.info("There are {} yielding queues", yielding);
                    TerminateNodeReply reply = TerminateNodeReply.newBuilder()
                        .setStatus(Status.newBuilder().setCode(Code.OK).build())
                        .setStage(TerminationStage.TS_CLOSING_STREAM)
                        .build();
                    observer.onNext(reply);
                    new ScanYieldingQueueTask(metadataStore).run();
                } else {
                    TerminateNodeReply reply = TerminateNodeReply.newBuilder()
                        .setStatus(Status.newBuilder().setCode(Code.OK).build())
                        .setStage(TerminationStage.TS_TERMINATED)
                        .build();
                    observer.onNext(reply);
                    observer.onCompleted();
                    return;
                }
            }

            if (context.getDeadline().timeRemaining(TimeUnit.SECONDS) < 1) {
                observer.onCompleted();
                return;
            }
            executorService.schedule(this, 1, TimeUnit.SECONDS);
        } catch (Throwable e) {
            observer.onError(e);
        }
    }
}
