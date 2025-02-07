/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.automq.stream.s3;

import com.automq.stream.s3.cache.LogCache;
import com.automq.stream.s3.cache.ReadDataBlock;
import com.automq.stream.s3.cache.S3BlockCache;
import com.automq.stream.s3.metadata.StreamMetadata;
import com.automq.stream.s3.metrics.TimerUtil;
import com.automq.stream.s3.metrics.operations.S3Operation;
import com.automq.stream.s3.metrics.stats.OperationMetricsStats;
import com.automq.stream.s3.model.StreamRecordBatch;
import com.automq.stream.s3.objects.ObjectManager;
import com.automq.stream.s3.operator.S3Operator;
import com.automq.stream.s3.streams.StreamManager;
import com.automq.stream.s3.wal.WriteAheadLog;
import com.automq.stream.utils.FutureUtil;
import com.automq.stream.utils.ThreadUtils;
import com.automq.stream.utils.Threads;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


public class S3Storage implements Storage {
    private static final Logger LOGGER = LoggerFactory.getLogger(S3Storage.class);
    private final long maxWALCacheSize;
    private final Config config;
    private final WriteAheadLog log;
    /**
     * WAL log cache. Single thread mainReadExecutor will ensure the memory safety.
     */
    private final LogCache logCache;
    /**
     * WAL out of order callback sequencer. Single thread mainWriteExecutor will ensure the memory safety.
     */
    private final WALCallbackSequencer callbackSequencer = new WALCallbackSequencer();
    private final Queue<WALObjectUploadTaskContext> walObjectPrepareQueue = new LinkedList<>();
    private final Queue<WALObjectUploadTaskContext> walObjectCommitQueue = new LinkedList<>();
    private final List<CompletableFuture<Void>> inflightWALUploadTasks = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService mainWriteExecutor = Threads.newSingleThreadScheduledExecutor(
            ThreadUtils.createThreadFactory("s3-storage-main-write", false), LOGGER);
    private final ScheduledExecutorService mainReadExecutor = Threads.newSingleThreadScheduledExecutor(
            ThreadUtils.createThreadFactory("s3-storage-main-read", false), LOGGER);
    private final ScheduledExecutorService backgroundExecutor = Threads.newSingleThreadScheduledExecutor(
            ThreadUtils.createThreadFactory("s3-storage-background", true), LOGGER);
    private final ExecutorService uploadWALExecutor = Threads.newFixedThreadPool(
            4, ThreadUtils.createThreadFactory("s3-storage-upload-wal-%d", true), LOGGER);

    private final Queue<WalWriteRequest> backoffRecords = new LinkedBlockingQueue<>();
    private final ScheduledFuture<?> drainBackoffTask;
    private long lastLogTimestamp = 0L;

    private final StreamManager streamManager;
    private final ObjectManager objectManager;
    private final S3Operator s3Operator;
    private final S3BlockCache blockCache;

    public S3Storage(Config config, WriteAheadLog log, StreamManager streamManager, ObjectManager objectManager,
                     S3BlockCache blockCache, S3Operator s3Operator) {
        this.config = config;
        this.maxWALCacheSize = config.s3WALCacheSize();
        this.log = log;
        this.blockCache = blockCache;
        this.logCache = new LogCache(config.s3WALObjectSize(), config.s3MaxStreamNumPerWALObject());
        DirectByteBufAlloc.registerOOMHandlers(new LogCacheEvictOOMHandler());
        this.streamManager = streamManager;
        this.objectManager = objectManager;
        this.s3Operator = s3Operator;

        this.drainBackoffTask = this.backgroundExecutor.scheduleWithFixedDelay(this::tryDrainBackoffRecords, 100, 100, TimeUnit.MILLISECONDS);
    }

    @Override
    public void startup() {
        try {
            LOGGER.info("S3Storage starting");
            recover();
            LOGGER.info("S3Storage start completed");
        } catch (Throwable e) {
            LOGGER.error("S3Storage start fail", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Upload WAL to S3 and close opening streams.
     */
    private void recover() throws Throwable {
        log.start();
        List<StreamMetadata> streams = streamManager.getOpeningStreams().get();

        LogCache.LogCacheBlock cacheBlock = recoverContinuousRecords(log.recover(), streams);
        Map<Long, Long> streamEndOffsets = new HashMap<>();
        cacheBlock.records().forEach((streamId, records) -> {
            if (!records.isEmpty()) {
                streamEndOffsets.put(streamId, records.get(records.size() - 1).getLastOffset());
            }
        });

        if (cacheBlock.size() != 0) {
            LOGGER.info("try recover from crash, recover records bytes size {}", cacheBlock.size());
            uploadWALObject(cacheBlock).get();
            cacheBlock.records().forEach((streamId, records) -> records.forEach(StreamRecordBatch::release));
        }
        log.reset().get();
        for (StreamMetadata stream : streams) {
            long newEndOffset = streamEndOffsets.getOrDefault(stream.getStreamId(), stream.getEndOffset());
            LOGGER.info("recover try close stream {} with new end offset {}", stream, newEndOffset);
        }
        CompletableFuture.allOf(
                streams
                        .stream()
                        .map(s -> streamManager.closeStream(s.getStreamId(), s.getEpoch()))
                        .toArray(CompletableFuture[]::new)
        ).get();
    }

    LogCache.LogCacheBlock recoverContinuousRecords(Iterator<WriteAheadLog.RecoverResult> it, List<StreamMetadata> openingStreams) {
        Map<Long, Long> openingStreamEndOffsets = openingStreams.stream().collect(Collectors.toMap(StreamMetadata::getStreamId, StreamMetadata::getEndOffset));
        LogCache.LogCacheBlock cacheBlock = new LogCache.LogCacheBlock(1024L * 1024 * 1024);
        long logEndOffset = -1L;
        Map<Long, Long> streamNextOffsets = new HashMap<>();
        while (it.hasNext()) {
            WriteAheadLog.RecoverResult recoverResult = it.next();
            logEndOffset = recoverResult.recordOffset();
            ByteBuf recordBuf = recoverResult.record().duplicate();
            StreamRecordBatch streamRecordBatch = StreamRecordBatchCodec.decode(recordBuf);
            long streamId = streamRecordBatch.getStreamId();
            Long openingStreamEndOffset = openingStreamEndOffsets.get(streamId);
            if (openingStreamEndOffset == null) {
                // stream is already safe closed. so skip the stream records.
                recordBuf.release();
                continue;
            }
            if (streamRecordBatch.getBaseOffset() < openingStreamEndOffset) {
                // filter committed records.
                recordBuf.release();
                continue;
            }
            Long expectNextOffset = streamNextOffsets.get(streamId);
            if (expectNextOffset == null || expectNextOffset == streamRecordBatch.getBaseOffset()) {
                cacheBlock.put(streamRecordBatch);
                streamNextOffsets.put(streamRecordBatch.getStreamId(), streamRecordBatch.getLastOffset());
            } else {
                LOGGER.error("unexpected WAL record, streamId={}, expectNextOffset={}, record={}", streamId, expectNextOffset, streamRecordBatch);
                streamRecordBatch.release();
            }
        }
        if (logEndOffset >= 0L) {
            cacheBlock.confirmOffset(logEndOffset);
        }
        cacheBlock.records().forEach((streamId, records) -> {
            if (!records.isEmpty()) {
                long startOffset = records.get(0).getBaseOffset();
                long expectedStartOffset = openingStreamEndOffsets.getOrDefault(streamId, startOffset);
                if (startOffset > expectedStartOffset) {
                    throw new IllegalStateException(String.format("[BUG] WAL data may lost, streamId %s endOffset=%s from controller" +
                            "but WAL recovered records startOffset=%s", streamId, expectedStartOffset, startOffset));
                }
            }

        });

        return cacheBlock;
    }

    @Override
    public void shutdown() {
        drainBackoffTask.cancel(false);
        FutureUtil.suppress(drainBackoffTask::get, LOGGER);
        for (WalWriteRequest request : backoffRecords) {
            request.cf.completeExceptionally(new IOException("S3Storage is shutdown"));
        }
        log.shutdownGracefully();
        backgroundExecutor.shutdown();
        mainReadExecutor.shutdown();
        mainWriteExecutor.shutdown();
    }


    @Override
    public CompletableFuture<Void> append(StreamRecordBatch streamRecord) {
        TimerUtil timerUtil = new TimerUtil();
        CompletableFuture<Void> cf = new CompletableFuture<>();
        // encoded before append to free heap ByteBuf.
        streamRecord.encoded();
        WalWriteRequest writeRequest = new WalWriteRequest(streamRecord, -1L, cf);
        handleAppendRequest(writeRequest);
        append0(writeRequest, false);
        cf.whenComplete((nil, ex) -> {
            streamRecord.release();
            OperationMetricsStats.getHistogram(S3Operation.APPEND_STORAGE).update(timerUtil.elapsed());
        });
        return cf;
    }

    /**
     * Append record to WAL.
     * @return backoff status.
     */
    public boolean append0(WalWriteRequest request, boolean fromBackoff) {
        // TODO: storage status check, fast fail the request when storage closed.
        if (!fromBackoff && !backoffRecords.isEmpty()) {
            backoffRecords.offer(request);
            return true;
        }
        if (!tryAcquirePermit()) {
            if (!fromBackoff) {
                backoffRecords.offer(request);
            }
            OperationMetricsStats.getCounter(S3Operation.APPEND_STORAGE_LOG_CACHE_FULL).inc();
            if (System.currentTimeMillis() - lastLogTimestamp > 1000L) {
                LOGGER.warn("[BACKOFF] log cache size {} is larger than {}", logCache.size(), maxWALCacheSize);
                lastLogTimestamp = System.currentTimeMillis();
            }
            return true;
        }
        WriteAheadLog.AppendResult appendResult;
        try {
            StreamRecordBatch streamRecord = request.record;
            streamRecord.retain();
            appendResult = log.append(streamRecord.encoded());
        } catch (WriteAheadLog.OverCapacityException e) {
            // the WAL write data align with block, 'WAL is full but LogCacheBlock is not full' may happen.
            forceUpload(LogCache.MATCH_ALL_STREAMS);
            if (!fromBackoff) {
                backoffRecords.offer(request);
            }
            if (System.currentTimeMillis() - lastLogTimestamp > 1000L) {
                LOGGER.warn("[BACKOFF] log over capacity", e);
                lastLogTimestamp = System.currentTimeMillis();
            }
            return true;
        }
        request.offset = appendResult.recordOffset();
        appendResult.future().thenAccept(nil -> handleAppendCallback(request));
        return false;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean tryAcquirePermit() {
        return logCache.size() < maxWALCacheSize;
    }

    private void tryDrainBackoffRecords() {
        try {
            for (; ; ) {
                WalWriteRequest request = backoffRecords.peek();
                if (request == null) {
                    break;
                }
                if (append0(request, true)) {
                    LOGGER.warn("try drain backoff record fail, still backoff");
                    break;
                }
                backoffRecords.poll();
            }
        } catch (Throwable e) {
            LOGGER.error("[UNEXPECTED] tryDrainBackoffRecords fail", e);
        }
    }

    @Override
    public CompletableFuture<ReadDataBlock> read(long streamId, long startOffset, long endOffset, int maxBytes) {
        TimerUtil timerUtil = new TimerUtil();
        CompletableFuture<ReadDataBlock> cf = new CompletableFuture<>();
        mainReadExecutor.execute(() -> FutureUtil.propagate(read0(streamId, startOffset, endOffset, maxBytes), cf));
        cf.whenComplete((nil, ex) -> {
            OperationMetricsStats.getHistogram(S3Operation.READ_STORAGE).update(timerUtil.elapsed());
        });
        return cf;
    }

    private CompletableFuture<ReadDataBlock> read0(long streamId, long startOffset, long endOffset, int maxBytes) {
        List<StreamRecordBatch> logCacheRecords = logCache.get(streamId, startOffset, endOffset, maxBytes);
        if (!logCacheRecords.isEmpty() && logCacheRecords.get(0).getBaseOffset() <= startOffset) {
            return CompletableFuture.completedFuture(new ReadDataBlock(logCacheRecords));
        }
        if (!logCacheRecords.isEmpty()) {
            endOffset = logCacheRecords.get(0).getBaseOffset();
        }
        return blockCache.read(streamId, startOffset, endOffset, maxBytes).thenApplyAsync(readDataBlock -> {
            List<StreamRecordBatch> rst = new ArrayList<>(readDataBlock.getRecords());
            int remainingBytesSize = maxBytes - rst.stream().mapToInt(StreamRecordBatch::size).sum();
            int readIndex = -1;
            for (int i = 0; i < logCacheRecords.size() && remainingBytesSize > 0; i++) {
                readIndex = i;
                StreamRecordBatch record = logCacheRecords.get(i);
                rst.add(record);
                remainingBytesSize -= record.size();
            }
            if (readIndex < logCacheRecords.size()) {
                // release unnecessary record
                logCacheRecords.subList(readIndex + 1, logCacheRecords.size()).forEach(StreamRecordBatch::release);
            }
            continuousCheck(rst);
            return new ReadDataBlock(rst);
        }, mainReadExecutor).whenComplete((rst, ex) -> {
            if (ex != null) {
                logCacheRecords.forEach(StreamRecordBatch::release);
            }
        });
    }

    private void continuousCheck(List<StreamRecordBatch> records) {
        long expectStartOffset = -1L;
        for (StreamRecordBatch record : records) {
            if (expectStartOffset == -1L || record.getBaseOffset() == expectStartOffset) {
                expectStartOffset = record.getLastOffset();
            } else {
                throw new IllegalArgumentException("Continuous check fail" + records);
            }
        }
    }

    /**
     * Force upload stream WAL cache to S3. Use group upload to avoid generate too many S3 objects when broker shutdown.
     */
    @Override
    public synchronized CompletableFuture<Void> forceUpload(long streamId) {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        List<CompletableFuture<Void>> inflightWALUploadTasks = new ArrayList<>(this.inflightWALUploadTasks);
        // await inflight WAL upload tasks to group force upload tasks.
        CompletableFuture.allOf(inflightWALUploadTasks.toArray(new CompletableFuture[0])).whenCompleteAsync((nil, ex) -> {
            logCache.setConfirmOffset(callbackSequencer.getWALConfirmOffset());
            Optional<LogCache.LogCacheBlock> blockOpt = logCache.archiveCurrentBlockIfContains(streamId);
            if (blockOpt.isPresent()) {
                blockOpt.ifPresent(this::uploadWALObject);
            }
            FutureUtil.propagate(CompletableFuture.allOf(this.inflightWALUploadTasks.toArray(new CompletableFuture[0])), cf);
            mainWriteExecutor.execute(() -> callbackSequencer.tryFree(streamId));
        }, mainReadExecutor);
        return cf;
    }

    private void handleAppendRequest(WalWriteRequest request) {
        mainWriteExecutor.execute(() -> callbackSequencer.before(request));
    }

    private void handleAppendCallback(WalWriteRequest request) {
        mainWriteExecutor.execute(() -> handleAppendCallback0(request));
    }

    private void handleAppendCallback0(WalWriteRequest request) {
        List<WalWriteRequest> waitingAckRequests = callbackSequencer.after(request);
        long walConfirmOffset = callbackSequencer.getWALConfirmOffset();
        waitingAckRequests.forEach(r -> r.record.retain());
        mainReadExecutor.execute(() -> {
            for (WalWriteRequest waitingAckRequest : waitingAckRequests) {
                if (logCache.put(waitingAckRequest.record)) {
                    // cache block is full, trigger WAL object upload.
                    logCache.setConfirmOffset(walConfirmOffset);
                    LogCache.LogCacheBlock logCacheBlock = logCache.archiveCurrentBlock();
                    uploadWALObject(logCacheBlock);
                }
            }
        });
        for (WalWriteRequest waitingAckRequest : waitingAckRequests) {
            waitingAckRequest.cf.complete(null);
        }
    }

    /**
     * Upload cache block to S3. The earlier cache block will have smaller objectId and commit first.
     */
    CompletableFuture<Void> uploadWALObject(LogCache.LogCacheBlock logCacheBlock) {
        TimerUtil timerUtil = new TimerUtil();
        CompletableFuture<Void> cf = new CompletableFuture<>();
        inflightWALUploadTasks.add(cf);
        backgroundExecutor.execute(() -> FutureUtil.exec(() -> uploadWALObject0(logCacheBlock, cf), cf, LOGGER, "uploadWALObject"));
        cf.whenComplete((nil, ex) -> {
            OperationMetricsStats.getHistogram(S3Operation.UPLOAD_STORAGE_WAL).update(timerUtil.elapsed());
            inflightWALUploadTasks.remove(cf);
            if (ex != null) {
                LOGGER.error("upload WAL object fail", ex);
            }
        });
        return cf;
    }

    private void uploadWALObject0(LogCache.LogCacheBlock logCacheBlock, CompletableFuture<Void> cf) {
        WALObjectUploadTask walObjectUploadTask = WALObjectUploadTask.of(config, logCacheBlock.records(), objectManager, s3Operator, uploadWALExecutor);
        WALObjectUploadTaskContext context = new WALObjectUploadTaskContext();
        context.task = walObjectUploadTask;
        context.cache = logCacheBlock;
        context.cf = cf;

        boolean walObjectPrepareQueueEmpty = walObjectPrepareQueue.isEmpty();
        walObjectPrepareQueue.add(context);
        if (!walObjectPrepareQueueEmpty) {
            // there is another WAL object upload task is preparing, just return.
            return;
        }
        prepareWALObject(context);
    }

    private void prepareWALObject(WALObjectUploadTaskContext context) {
        context.task.prepare().thenAcceptAsync(nil -> {
            // 1. poll out current task and trigger upload.
            WALObjectUploadTaskContext peek = walObjectPrepareQueue.poll();
            Objects.requireNonNull(peek).task.upload();
            // 2. add task to commit queue.
            boolean walObjectCommitQueueEmpty = walObjectCommitQueue.isEmpty();
            walObjectCommitQueue.add(peek);
            if (walObjectCommitQueueEmpty) {
                commitWALObject(peek);
            }
            // 3. trigger next task to prepare.
            WALObjectUploadTaskContext next = walObjectPrepareQueue.peek();
            if (next != null) {
                prepareWALObject(next);
            }
        }, backgroundExecutor);
    }

    private void commitWALObject(WALObjectUploadTaskContext context) {
        context.task.commit().thenAcceptAsync(nil -> {
            // 1. poll out current task
            walObjectCommitQueue.poll();
            if (context.cache.confirmOffset() != 0) {
                LOGGER.info("try trim WAL to {}", context.cache.confirmOffset());
                log.trim(context.cache.confirmOffset());
            }
            // transfer records ownership to block cache.
            freeCache(context.cache);
            context.cf.complete(null);

            // 2. trigger next task to commit.
            WALObjectUploadTaskContext next = walObjectCommitQueue.peek();
            if (next != null) {
                commitWALObject(next);
            }
        }, backgroundExecutor).exceptionally(ex -> {
            LOGGER.error("Unexpected exception when commit WAL object", ex);
            context.cf.completeExceptionally(ex);
            System.err.println("Unexpected exception when commit WAL object");
            //noinspection CallToPrintStackTrace
            ex.printStackTrace();
            System.exit(1);
            return null;
        });
    }

    private void freeCache(LogCache.LogCacheBlock cacheBlock) {
        mainReadExecutor.execute(() -> logCache.markFree(cacheBlock));
    }

    /**
     * WALCallbackSequencer is modified in single thread mainExecutor.
     */
    static class WALCallbackSequencer {
        public static final long NOOP_OFFSET = -1L;
        private final Map<Long, Queue<WalWriteRequest>> stream2requests = new HashMap<>();
        private final BlockingQueue<WalWriteRequest> walRequests = new LinkedBlockingQueue<>();
        private long walConfirmOffset = NOOP_OFFSET;

        /**
         * Add request to stream sequence queue.
         */
        public void before(WalWriteRequest request) {
            try {
                walRequests.put(request);
                Queue<WalWriteRequest> streamRequests = stream2requests.computeIfAbsent(request.record.getStreamId(), s -> new LinkedBlockingQueue<>());
                streamRequests.add(request);
            } catch (Throwable ex) {
                request.cf.completeExceptionally(ex);
            }
        }

        /**
         * Try pop sequence persisted request from stream queue and move forward wal inclusive confirm offset.
         *
         * @return popped sequence persisted request.
         */
        public List<WalWriteRequest> after(WalWriteRequest request) {
            request.persisted = true;
            // move the WAL inclusive confirm offset.
            for (; ; ) {
                WalWriteRequest peek = walRequests.peek();
                if (peek == null || !peek.persisted) {
                    break;
                }
                walRequests.poll();
                walConfirmOffset = peek.offset;
            }

            // pop sequence success stream request.
            long streamId = request.record.getStreamId();
            Queue<WalWriteRequest> streamRequests = stream2requests.get(streamId);
            WalWriteRequest peek = streamRequests.peek();
            if (peek == null || peek.offset != request.offset) {
                return Collections.emptyList();
            }
            List<WalWriteRequest> rst = new ArrayList<>();
            rst.add(streamRequests.poll());
            for (; ; ) {
                peek = streamRequests.peek();
                if (peek == null || !peek.persisted) {
                    break;
                }
                rst.add(streamRequests.poll());
            }
            return rst;
        }

        /**
         * Get WAL inclusive confirm offset.
         *
         * @return inclusive confirm offset.
         */
        public long getWALConfirmOffset() {
            return walConfirmOffset;
        }

        /**
         * Try free stream related resources.
         */
        public void tryFree(long streamId) {
            Queue<?> queue = stream2requests.get(streamId);
            if (queue != null && queue.isEmpty()) {
                stream2requests.remove(streamId, queue);
            }
        }
    }

    class LogCacheEvictOOMHandler implements DirectByteBufAlloc.OOMHandler {
        @Override
        public int handle(int memoryRequired) {
            try {
                CompletableFuture<Integer> cf = new CompletableFuture<>();
                mainReadExecutor.submit(() -> FutureUtil.exec(() -> cf.complete(logCache.forceFree(memoryRequired)), cf, LOGGER, "handleOOM"));
                return cf.get();
            } catch (Throwable e) {
                return 0;
            }
        }
    }

    static class WALObjectUploadTaskContext {
        WALObjectUploadTask task;
        LogCache.LogCacheBlock cache;
        CompletableFuture<Void> cf;
    }
}
