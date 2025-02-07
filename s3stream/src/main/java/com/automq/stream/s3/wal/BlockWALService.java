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

package com.automq.stream.s3.wal;

import com.automq.stream.s3.Config;
import com.automq.stream.s3.DirectByteBufAlloc;
import com.automq.stream.s3.metrics.TimerUtil;
import com.automq.stream.s3.metrics.operations.S3Operation;
import com.automq.stream.s3.metrics.stats.OperationMetricsStats;
import com.automq.stream.s3.wal.util.WALChannel;
import com.automq.stream.s3.wal.util.WALUtil;
import com.automq.stream.utils.ThreadUtils;
import com.automq.stream.utils.Threads;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import org.apache.commons.lang3.time.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;

/**
 * /**
 * BlockWALService provides an infinite WAL, which is implemented based on block devices.
 * The capacity of the block device is configured by the application and may be smaller than the system allocation.
 * <p>
 * Usage:
 * <p>
 * 1. Call {@link BlockWALService#start} to start the service. Any other methods will throw an
 * {@link IllegalStateException} if called before {@link BlockWALService#start}.
 * <p>
 * 2. Call {@link BlockWALService#recover} to recover all untrimmed records if any.
 * <p>
 * 3. Call {@link BlockWALService#reset} to reset the service. This will clear all records, so make sure
 * all recovered records are processed before calling this method.
 * <p>
 * 4. Call {@link BlockWALService#append} to append records. As records are written in a circular way similar to
 * RingBuffer, if the caller does not call {@link BlockWALService#trim} in time, an {@link OverCapacityException}
 * will be thrown when calling {@link BlockWALService#append}.
 * <p>
 * 5. Call {@link BlockWALService#shutdownGracefully} to shut down the service gracefully, which will wait for
 * all pending writes to complete.
 * <p>
 * Implementation:
 * <p>
 * WAL Header
 * <p>
 * There are {@link BlockWALService#WAL_HEADER_COUNT} WAL headers, each of which is {@link WALUtil#BLOCK_SIZE} bytes.
 * Every {@link BlockWALService#walHeaderFlushIntervalSeconds}, the service will flush the WAL header to the block
 * device. The WAL header is used to record the meta information of the WAL, and is used to recover the WAL when
 * the service is restarted.
 * <p>
 * Layout:
 * <p>
 * 0 - [4B] {@link WALHeaderCoreData#magicCode0} Magic code of the WAL header, used to verify the start of the WAL header
 * <p>
 * 1 - [8B] {@link WALHeaderCoreData#capacity1} Capacity of the block device, which is configured by the application
 * and should not be modified after the first start of the service
 * <p>
 * 2 - [8B] {@link WALHeaderCoreData#trimOffset2} The logical start offset of the WAL, records before which are
 * considered useless and have been deleted
 * <p>
 * 3 - [8B] {@link WALHeaderCoreData#lastWriteTimestamp3} The timestamp of the last write to the WAL header, used to
 * determine which WAL header is the latest when recovering
 * <p>
 * 4 - [8B] {@link WALHeaderCoreData#slidingWindowNextWriteOffset4} The offset of the next record to be written
 * in the sliding window
 * <p>
 * 5 - [8B] {@link WALHeaderCoreData#slidingWindowStartOffset5} The start offset of the sliding window, all records
 * before this offset have been successfully written to the block device
 * <p>
 * 6 - [8B] {@link WALHeaderCoreData#slidingWindowMaxLength6} The maximum size of the sliding window, which can be
 * scaled up when needed, and is used to determine when to stop recovering
 * <p>
 * 7 - [4B] {@link WALHeaderCoreData#shutdownType7} The shutdown type of the service, {@link ShutdownType#GRACEFULLY} or
 * {@link ShutdownType#UNGRACEFULLY}
 * <p>
 * 8 - [4B] {@link WALHeaderCoreData#crc8} CRC of the rest of the WAL header, used to verify the correctness of the
 * WAL header
 * <p>
 * Sliding Window
 * <p>
 * The sliding window contains all records that have not been successfully written to the block device.
 * So when recovering, we only need to try to recover the records in the sliding window.
 * <p>
 * Record Header
 * <p>
 * Layout:
 * <p>
 * 0 - [4B] {@link SlidingWindowService.RecordHeaderCoreData#getMagicCode} Magic code of the record header,
 * used to verify the start of the record header
 * <p>
 * 1 - [4B] {@link SlidingWindowService.RecordHeaderCoreData#getRecordBodyLength} The length of the record body
 * <p>
 * 2 - [8B] {@link SlidingWindowService.RecordHeaderCoreData#getRecordBodyOffset} The logical start offset of the record body
 * <p>
 * 3 - [4B] {@link SlidingWindowService.RecordHeaderCoreData#getRecordBodyCRC} CRC of the record body, used to verify
 * the correctness of the record body
 * <p>
 * 4 - [4B] {@link SlidingWindowService.RecordHeaderCoreData#getRecordHeaderCRC} CRC of the rest of the record header,
 * used to verify the correctness of the record header
 */
public class BlockWALService implements WriteAheadLog {
    public static final int RECORD_HEADER_SIZE = 4 + 4 + 8 + 4 + 4;
    public static final int RECORD_HEADER_WITHOUT_CRC_SIZE = RECORD_HEADER_SIZE - 4;
    public static final int RECORD_HEADER_MAGIC_CODE = 0x87654321;
    public static final int WAL_HEADER_MAGIC_CODE = 0x12345678;
    public static final int WAL_HEADER_SIZE = 4 + 8 + 8 + 8 + 8 + 8 + 8 + 4 + 4;
    public static final int WAL_HEADER_WITHOUT_CRC_SIZE = WAL_HEADER_SIZE - 4;
    public static final int WAL_HEADER_COUNT = 2;
    public static final int WAL_HEADER_CAPACITY = WALUtil.BLOCK_SIZE;
    public static final int WAL_HEADER_TOTAL_CAPACITY = WAL_HEADER_CAPACITY * WAL_HEADER_COUNT;
    private static final Logger LOGGER = LoggerFactory.getLogger(BlockWALService.class);
    private final AtomicBoolean readyToServe = new AtomicBoolean(false);
    private final AtomicLong writeHeaderRoundTimes = new AtomicLong(0);
    private int walHeaderFlushIntervalSeconds;
    private long initialWindowSize;
    private ScheduledExecutorService flushWALHeaderScheduler;
    private WALChannel walChannel;
    private SlidingWindowService slidingWindowService;
    private WALHeaderCoreData walHeaderCoreData;

    public static BlockWALServiceBuilder builder(String blockDevicePath, long capacity) {
        return new BlockWALServiceBuilder(blockDevicePath, capacity);
    }

    private void startFlushWALHeaderScheduler() {
        this.flushWALHeaderScheduler = Threads.newSingleThreadScheduledExecutor(
                ThreadUtils.createThreadFactory("flush-wal-header-thread-%d", true), LOGGER);
        this.flushWALHeaderScheduler.scheduleAtFixedRate(() -> {
            try {
                BlockWALService.this.flushWALHeader(
                        this.slidingWindowService.getWindowCoreData().getWindowStartOffset(),
                        this.slidingWindowService.getWindowCoreData().getWindowMaxLength(),
                        this.slidingWindowService.getWindowCoreData().getWindowNextWriteOffset(),
                        ShutdownType.UNGRACEFULLY);
            } catch (IOException ignored) {
            }
        }, walHeaderFlushIntervalSeconds, walHeaderFlushIntervalSeconds, TimeUnit.SECONDS);
    }

    @Deprecated
    private void flushWALHeader(long windowStartOffset,
                                long windowMaxLength,
                                long windowNextWriteOffset,
                                ShutdownType shutdownType
    ) throws IOException {
        walHeaderCoreData
                .setSlidingWindowStartOffset(windowStartOffset)
                .setSlidingWindowMaxLength(windowMaxLength)
                .setSlidingWindowNextWriteOffset(windowNextWriteOffset)
                .setShutdownType(shutdownType);
        flushWALHeader();
    }

    private synchronized void flushWALHeader() throws IOException {
        long position = writeHeaderRoundTimes.getAndIncrement() % WAL_HEADER_COUNT * WAL_HEADER_CAPACITY;
        try {
            walHeaderCoreData.setLastWriteTimestamp(System.nanoTime());
            long trimOffset = walHeaderCoreData.getTrimOffset();
            ByteBuf buf = walHeaderCoreData.marshal();
            this.walChannel.write(buf, position);
            buf.release();
            walHeaderCoreData.setFlushedTrimOffset(trimOffset);
            LOGGER.debug("WAL header flushed, position: {}, header: {}", position, walHeaderCoreData);
        } catch (IOException e) {
            LOGGER.error("failed to flush WAL header, position: {}, header: {}", position, walHeaderCoreData, e);
            throw e;
        }
    }

    /**
     * Try to read a record at the given offset.
     * The returned record should be released by the caller.
     *
     * @throws ReadRecordException if the record is not found or the record is corrupted
     */
    private ByteBuf readRecord(long recordSectionCapacity, long recoverStartOffset) throws ReadRecordException {
        final ByteBuf recordHeader = DirectByteBufAlloc.byteBuffer(RECORD_HEADER_SIZE);
        SlidingWindowService.RecordHeaderCoreData readRecordHeader;
        try {
            readRecordHeader = parseRecordHeader(recordSectionCapacity, recoverStartOffset, recordHeader);
        } finally {
            recordHeader.release();
        }

        int recordBodyLength = readRecordHeader.getRecordBodyLength();
        ByteBuf recordBody = DirectByteBufAlloc.byteBuffer(recordBodyLength);
        try {
            parseRecordBody(recordSectionCapacity, recoverStartOffset, readRecordHeader, recordBody);
        } catch (ReadRecordException e) {
            recordBody.release();
            throw e;
        }

        return recordBody;
    }

    private SlidingWindowService.RecordHeaderCoreData parseRecordHeader(long recordSectionCapacity, long recoverStartOffset, ByteBuf recordHeader) throws ReadRecordException {
        final long position = WALUtil.recordOffsetToPosition(recoverStartOffset, recordSectionCapacity, WAL_HEADER_TOTAL_CAPACITY);
        try {
            int read = walChannel.read(recordHeader, position);
            if (read != RECORD_HEADER_SIZE) {
                throw new ReadRecordException(
                        WALUtil.alignNextBlock(recoverStartOffset),
                        String.format("failed to read record header: expected %d bytes, actual %d bytes, recoverStartOffset: %d", RECORD_HEADER_SIZE, read, recoverStartOffset)
                );
            }
        } catch (IOException e) {
            LOGGER.error("failed to read record header, position: {}, recoverStartOffset: {}", position, recoverStartOffset, e);
            throw new ReadRecordException(
                    WALUtil.alignNextBlock(recoverStartOffset),
                    String.format("failed to read record header, recoverStartOffset: %d", recoverStartOffset)
            );
        }

        SlidingWindowService.RecordHeaderCoreData readRecordHeader = SlidingWindowService.RecordHeaderCoreData.unmarshal(recordHeader);
        if (readRecordHeader.getMagicCode() != RECORD_HEADER_MAGIC_CODE) {
            throw new ReadRecordException(
                    WALUtil.alignNextBlock(recoverStartOffset),
                    String.format("magic code mismatch: expected %d, actual %d, recoverStartOffset: %d", RECORD_HEADER_MAGIC_CODE, readRecordHeader.getMagicCode(), recoverStartOffset)
            );
        }

        int recordHeaderCRC = readRecordHeader.getRecordHeaderCRC();
        int calculatedRecordHeaderCRC = WALUtil.crc32(recordHeader, RECORD_HEADER_WITHOUT_CRC_SIZE);
        if (recordHeaderCRC != calculatedRecordHeaderCRC) {
            throw new ReadRecordException(
                    WALUtil.alignNextBlock(recoverStartOffset),
                    String.format("record header crc mismatch: expected %d, actual %d, recoverStartOffset: %d", calculatedRecordHeaderCRC, recordHeaderCRC, recoverStartOffset)
            );
        }

        int recordBodyLength = readRecordHeader.getRecordBodyLength();
        if (recordBodyLength <= 0) {
            throw new ReadRecordException(
                    WALUtil.alignNextBlock(recoverStartOffset),
                    String.format("invalid record body length: %d, recoverStartOffset: %d", recordBodyLength, recoverStartOffset)
            );
        }

        long recordBodyOffset = readRecordHeader.getRecordBodyOffset();
        if (recordBodyOffset != recoverStartOffset + RECORD_HEADER_SIZE) {
            throw new ReadRecordException(
                    WALUtil.alignNextBlock(recoverStartOffset),
                    String.format("invalid record body offset: expected %d, actual %d, recoverStartOffset: %d", recoverStartOffset + RECORD_HEADER_SIZE, recordBodyOffset, recoverStartOffset)
            );
        }
        return readRecordHeader;
    }

    private void parseRecordBody(long recordSectionCapacity, long recoverStartOffset, SlidingWindowService.RecordHeaderCoreData readRecordHeader, ByteBuf recordBody) throws ReadRecordException {
        long recordBodyOffset = readRecordHeader.getRecordBodyOffset();
        int recordBodyLength = readRecordHeader.getRecordBodyLength();
        try {
            int read = walChannel.read(recordBody, WALUtil.recordOffsetToPosition(recordBodyOffset, recordSectionCapacity, WAL_HEADER_TOTAL_CAPACITY));
            if (read != recordBodyLength) {
                throw new ReadRecordException(
                        WALUtil.alignNextBlock(recoverStartOffset + RECORD_HEADER_SIZE + recordBodyLength),
                        String.format("failed to read record body: expected %d bytes, actual %d bytes, recoverStartOffset: %d", recordBodyLength, read, recoverStartOffset)
                );
            }
        } catch (IOException e) {
            LOGGER.error("failed to read record body, position: {}, recoverStartOffset: {}", recordBodyOffset, recoverStartOffset, e);
            throw new ReadRecordException(
                    WALUtil.alignNextBlock(recoverStartOffset + RECORD_HEADER_SIZE + recordBodyLength),
                    String.format("failed to read record body, recoverStartOffset: %d", recoverStartOffset)
            );
        }

        int recordBodyCRC = readRecordHeader.getRecordBodyCRC();
        int calculatedRecordBodyCRC = WALUtil.crc32(recordBody);
        if (recordBodyCRC != calculatedRecordBodyCRC) {
            throw new ReadRecordException(
                    WALUtil.alignNextBlock(recoverStartOffset + RECORD_HEADER_SIZE + recordBodyLength),
                    String.format("record body crc mismatch: expected %d, actual %d, recoverStartOffset: %d", calculatedRecordBodyCRC, recordBodyCRC, recoverStartOffset)
            );
        }
    }

    private WALHeaderCoreData recoverEntireWALAndCorrectWALHeader(WALHeaderCoreData paramWALHeader) {
        // initialize flushTrimOffset
        paramWALHeader.setFlushedTrimOffset(paramWALHeader.getTrimOffset());

        // graceful shutdown, no need to correct the header
        if (paramWALHeader.getShutdownType().equals(ShutdownType.GRACEFULLY)) {
            LOGGER.info("recovered from graceful shutdown, WALHeader: {}", paramWALHeader);
            return paramWALHeader;
        }

        // ungraceful shutdown, need to correct the header
        long recoverStartOffset = WALUtil.alignLargeByBlockSize(paramWALHeader.getSlidingWindowStartOffset());
        long recoverRemainingBytes = paramWALHeader.recordSectionCapacity();
        final long recordSectionCapacity = paramWALHeader.recordSectionCapacity();

        long nextRecoverStartOffset;
        long meetIllegalRecordTimes = 0;
        LOGGER.info("start to recover from ungraceful shutdown, recoverStartOffset: {}, recoverRemainingBytes: {}", recoverStartOffset, recoverRemainingBytes);
        do {
            try {
                ByteBuf body = readRecord(recordSectionCapacity, recoverStartOffset);
                nextRecoverStartOffset = recoverStartOffset + RECORD_HEADER_SIZE + body.readableBytes();
                body.release();
            } catch (ReadRecordException e) {
                nextRecoverStartOffset = e.getJumpNextRecoverOffset();
                LOGGER.debug("failed to read record, try next, recoverStartOffset: {}, meetIllegalRecordTimes: {}, recoverRemainingBytes: {}, error: {}",
                        recoverStartOffset, meetIllegalRecordTimes, recoverRemainingBytes, e.getMessage());
                meetIllegalRecordTimes++;
            }

            recoverRemainingBytes -= nextRecoverStartOffset - recoverStartOffset;
            recoverStartOffset = nextRecoverStartOffset;
        } while (recoverRemainingBytes > 0);
        long windowInitOffset = WALUtil.alignLargeByBlockSize(nextRecoverStartOffset);
        paramWALHeader.setSlidingWindowStartOffset(windowInitOffset).setSlidingWindowNextWriteOffset(windowInitOffset);

        LOGGER.info("recovered from ungraceful shutdown, WALHeader: {}", paramWALHeader);
        return paramWALHeader;
    }

    private void recoverWALHeader() throws IOException {
        WALHeaderCoreData walHeaderCoreDataAvailable = null;

        for (int i = 0; i < WAL_HEADER_COUNT; i++) {
            ByteBuf buf = DirectByteBufAlloc.byteBuffer(WAL_HEADER_SIZE);
            try {
                int read = walChannel.read(buf, i * WAL_HEADER_CAPACITY);
                if (read != WAL_HEADER_SIZE) {
                    continue;
                }
                WALHeaderCoreData walHeaderCoreData = WALHeaderCoreData.unmarshal(buf);
                if (walHeaderCoreDataAvailable == null || walHeaderCoreDataAvailable.lastWriteTimestamp3 < walHeaderCoreData.lastWriteTimestamp3) {
                    walHeaderCoreDataAvailable = walHeaderCoreData;
                }
            } catch (IOException | UnmarshalException ignored) {
                // failed to parse WALHeader, ignore
            } finally {
                buf.release();
            }
        }

        if (walHeaderCoreDataAvailable != null) {
            walHeaderCoreData = recoverEntireWALAndCorrectWALHeader(walHeaderCoreDataAvailable);
        } else {
            walHeaderCoreData = new WALHeaderCoreData()
                    .setCapacity(walChannel.capacity())
                    .setSlidingWindowMaxLength(initialWindowSize)
                    .setShutdownType(ShutdownType.UNGRACEFULLY);
            LOGGER.info("no valid WALHeader found, create new WALHeader: {}", walHeaderCoreData);
        }
        flushWALHeader();
        slidingWindowService.resetWindowWhenRecoverOver(
                walHeaderCoreData.getSlidingWindowStartOffset(),
                walHeaderCoreData.getSlidingWindowNextWriteOffset(),
                walHeaderCoreData.getSlidingWindowMaxLength()
        );
    }

    @Override
    public WriteAheadLog start() throws IOException {
        StopWatch stopWatch = StopWatch.createStarted();

        walChannel.open();
        recoverWALHeader();
        startFlushWALHeaderScheduler();
        slidingWindowService.start();
        readyToServe.set(true);

        LOGGER.info("block WAL service started, cost: {} ms", stopWatch.getTime(TimeUnit.MILLISECONDS));
        return this;
    }

    @Override
    public void shutdownGracefully() {
        StopWatch stopWatch = StopWatch.createStarted();

        readyToServe.set(false);
        flushWALHeaderScheduler.shutdown();
        try {
            if (!flushWALHeaderScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                flushWALHeaderScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            flushWALHeaderScheduler.shutdownNow();
        }

        boolean gracefulShutdown = slidingWindowService.shutdown(1, TimeUnit.DAYS);
        try {
            flushWALHeader(
                    slidingWindowService.getWindowCoreData().getWindowStartOffset(),
                    slidingWindowService.getWindowCoreData().getWindowMaxLength(),
                    slidingWindowService.getWindowCoreData().getWindowNextWriteOffset(),
                    gracefulShutdown ? ShutdownType.GRACEFULLY : ShutdownType.UNGRACEFULLY
            );
        } catch (IOException e) {
            LOGGER.error("failed to flush WALHeader when shutdown gracefully", e);
        }

        walChannel.close();

        LOGGER.info("block WAL service shutdown gracefully: {}, cost: {} ms", gracefulShutdown, stopWatch.getTime(TimeUnit.MILLISECONDS));
    }

    @Override
    public AppendResult append(ByteBuf buf, int crc) throws OverCapacityException {
        try {
            return append0(buf, crc);
        } catch (OverCapacityException ex) {
            buf.release();
            OperationMetricsStats.getCounter(S3Operation.APPEND_STORAGE_WAL_FULL).inc();
            throw ex;
        }
    }

    public AppendResult append0(ByteBuf body, int crc) throws OverCapacityException {
        TimerUtil timerUtil = new TimerUtil();
        checkReadyToServe();

        final long recordSize = RECORD_HEADER_SIZE + body.readableBytes();
        final CompletableFuture<AppendResult.CallbackResult> appendResultFuture = new CompletableFuture<>();
        long expectedWriteOffset;

        Lock lock = slidingWindowService.getBlockLock();
        lock.lock();
        try {
            Block block = slidingWindowService.getCurrentBlockLocked();
            expectedWriteOffset = block.addRecord(recordSize, (offset) -> record(body, crc, offset), appendResultFuture);
            if (expectedWriteOffset < 0) {
                // this block is full, create a new one
                block = slidingWindowService.sealAndNewBlockLocked(block, recordSize, walHeaderCoreData.getFlushedTrimOffset(), walHeaderCoreData.getCapacity() - WAL_HEADER_TOTAL_CAPACITY);
                expectedWriteOffset = block.addRecord(recordSize, (offset) -> record(body, crc, offset), appendResultFuture);
            }
        } finally {
            lock.unlock();
        }
        slidingWindowService.tryWriteBlock();

        final AppendResult appendResult = new AppendResultImpl(expectedWriteOffset, appendResultFuture);
        appendResult.future().whenComplete((nil, ex) -> {
            OperationMetricsStats.getHistogram(S3Operation.APPEND_STORAGE_WAL).update(timerUtil.elapsed());
        });
        return appendResult;
    }

    private ByteBuf recordHeader(ByteBuf body, int crc, long start) {
        return new SlidingWindowService.RecordHeaderCoreData()
                .setMagicCode(RECORD_HEADER_MAGIC_CODE)
                .setRecordBodyLength(body.readableBytes())
                .setRecordBodyOffset(start + RECORD_HEADER_SIZE)
                .setRecordBodyCRC(crc)
                .marshal();
    }

    private ByteBuf record(ByteBuf body, int crc, long start) {
        CompositeByteBuf record = DirectByteBufAlloc.compositeByteBuffer();
        crc = 0 == crc ? WALUtil.crc32(body) : crc;
        record.addComponents(true, recordHeader(body, crc, start), body);
        return record;
    }

    @Override
    public Iterator<RecoverResult> recover() {
        checkReadyToServe();

        long trimmedOffset = walHeaderCoreData.getTrimOffset();
        return recover(trimmedOffset);
    }

    /**
     * Recover from the given offset.
     */
    private Iterator<RecoverResult> recover(long startOffset) {
        long recoverStartOffset = WALUtil.alignSmallByBlockSize(startOffset);
        RecoverIterator iterator;
        if (startOffset == 0) {
            iterator = new RecoverIterator(recoverStartOffset);
        } else {
            iterator = new RecoverIterator(recoverStartOffset, startOffset);
        }
        return iterator;
    }

    @Override
    public CompletableFuture<Void> reset() {
        checkReadyToServe();

        long previousNextWriteOffset = slidingWindowService.getWindowCoreData().getWindowNextWriteOffset();
        slidingWindowService.resetWindow(previousNextWriteOffset + WALUtil.BLOCK_SIZE);
        LOGGER.info("reset sliding window and trim WAL to offset: {}", previousNextWriteOffset);
        return trim(previousNextWriteOffset);
    }

    @Override
    public CompletableFuture<Void> trim(long offset) {
        checkReadyToServe();

        if (offset >= slidingWindowService.getWindowCoreData().getWindowStartOffset()) {
            throw new IllegalArgumentException("failed to trim: record at offset " + offset + " has not been flushed yet");
        }

        walHeaderCoreData.updateTrimOffset(offset);
        return CompletableFuture.runAsync(() -> {
            // TODO: more beautiful
            this.walHeaderCoreData.setSlidingWindowStartOffset(slidingWindowService.getWindowCoreData().getWindowStartOffset());
            this.walHeaderCoreData.setSlidingWindowNextWriteOffset(slidingWindowService.getWindowCoreData().getWindowNextWriteOffset());
            this.walHeaderCoreData.setSlidingWindowMaxLength(slidingWindowService.getWindowCoreData().getWindowMaxLength());
            try {
                flushWALHeader();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, flushWALHeaderScheduler);
    }

    private void checkReadyToServe() {
        if (!readyToServe.get()) {
            throw new IllegalStateException("WriteAheadLog is not ready to serve");
        }
    }

    private SlidingWindowService.WALHeaderFlusher flusher() {
        return windowMaxLength -> flushWALHeader(
                slidingWindowService.getWindowCoreData().getWindowStartOffset(),
                windowMaxLength,
                slidingWindowService.getWindowCoreData().getWindowNextWriteOffset(),
                ShutdownType.UNGRACEFULLY
        );
    }

    static class WALHeaderCoreData {
        private final AtomicLong trimOffset2 = new AtomicLong(0);
        private final AtomicLong flushedTrimOffset = new AtomicLong(0);
        private final AtomicLong slidingWindowStartOffset5 = new AtomicLong(0);
        private final AtomicLong slidingWindowNextWriteOffset4 = new AtomicLong(0);
        private final AtomicLong slidingWindowMaxLength6 = new AtomicLong(0);
        private int magicCode0 = WAL_HEADER_MAGIC_CODE;
        private long capacity1;
        private long lastWriteTimestamp3 = System.nanoTime();
        private ShutdownType shutdownType7 = ShutdownType.UNGRACEFULLY;
        private int crc8;

        public static WALHeaderCoreData unmarshal(ByteBuf buf) throws UnmarshalException {
            WALHeaderCoreData walHeaderCoreData = new WALHeaderCoreData();
            buf.markReaderIndex();
            walHeaderCoreData.magicCode0 = buf.readInt();
            walHeaderCoreData.capacity1 = buf.readLong();
            walHeaderCoreData.trimOffset2.set(buf.readLong());
            walHeaderCoreData.lastWriteTimestamp3 = buf.readLong();
            walHeaderCoreData.slidingWindowNextWriteOffset4.set(buf.readLong());
            walHeaderCoreData.slidingWindowStartOffset5.set(buf.readLong());
            walHeaderCoreData.slidingWindowMaxLength6.set(buf.readLong());
            walHeaderCoreData.shutdownType7 = ShutdownType.fromCode(buf.readInt());
            walHeaderCoreData.crc8 = buf.readInt();
            buf.resetReaderIndex();

            if (walHeaderCoreData.magicCode0 != WAL_HEADER_MAGIC_CODE) {
                throw new UnmarshalException(String.format("WALHeader MagicCode not match, Recovered: [%d] expect: [%d]", walHeaderCoreData.magicCode0, WAL_HEADER_MAGIC_CODE));
            }

            int crc = WALUtil.crc32(buf, WAL_HEADER_WITHOUT_CRC_SIZE);
            if (crc != walHeaderCoreData.crc8) {
                throw new UnmarshalException(String.format("WALHeader CRC not match, Recovered: [%d] expect: [%d]", walHeaderCoreData.crc8, crc));
            }

            return walHeaderCoreData;
        }

        public long recordSectionCapacity() {
            return capacity1 - WAL_HEADER_TOTAL_CAPACITY;
        }

        public long getCapacity() {
            return capacity1;
        }

        public WALHeaderCoreData setCapacity(long capacity) {
            this.capacity1 = capacity;
            return this;
        }

        public long getSlidingWindowStartOffset() {
            return slidingWindowStartOffset5.get();
        }

        public WALHeaderCoreData setSlidingWindowStartOffset(long slidingWindowStartOffset) {
            this.slidingWindowStartOffset5.set(slidingWindowStartOffset);
            return this;
        }

        public long getTrimOffset() {
            return trimOffset2.get();
        }

        // Update the trim offset if the given trim offset is larger than the current one.
        public WALHeaderCoreData updateTrimOffset(long trimOffset) {
            trimOffset2.accumulateAndGet(trimOffset, Math::max);
            return this;
        }

        public long getFlushedTrimOffset() {
            return flushedTrimOffset.get();
        }

        public void setFlushedTrimOffset(long flushedTrimOffset) {
            this.flushedTrimOffset.set(flushedTrimOffset);
        }

        public WALHeaderCoreData setLastWriteTimestamp(long lastWriteTimestamp) {
            this.lastWriteTimestamp3 = lastWriteTimestamp;
            return this;
        }

        public long getSlidingWindowNextWriteOffset() {
            return slidingWindowNextWriteOffset4.get();
        }

        public WALHeaderCoreData setSlidingWindowNextWriteOffset(long slidingWindowNextWriteOffset) {
            this.slidingWindowNextWriteOffset4.set(slidingWindowNextWriteOffset);
            return this;
        }

        public long getSlidingWindowMaxLength() {
            return slidingWindowMaxLength6.get();
        }

        public WALHeaderCoreData setSlidingWindowMaxLength(long slidingWindowMaxLength) {
            this.slidingWindowMaxLength6.set(slidingWindowMaxLength);
            return this;
        }

        public ShutdownType getShutdownType() {
            return shutdownType7;
        }

        public WALHeaderCoreData setShutdownType(ShutdownType shutdownType) {
            this.shutdownType7 = shutdownType;
            return this;
        }

        @Override
        public String toString() {
            return "WALHeader{"
                    + "magicCode=" + magicCode0
                    + ", capacity=" + capacity1
                    + ", trimOffset=" + trimOffset2
                    + ", lastWriteTimestamp=" + lastWriteTimestamp3
                    + ", nextWriteOffset=" + slidingWindowNextWriteOffset4
                    + ", slidingWindowStartOffset=" + slidingWindowStartOffset5
                    + ", slidingWindowMaxLength=" + slidingWindowMaxLength6
                    + ", shutdownType=" + shutdownType7
                    + ", crc=" + crc8
                    + '}';
        }

        private ByteBuf marshalHeaderExceptCRC() {
            ByteBuf buf = DirectByteBufAlloc.byteBuffer(WAL_HEADER_SIZE);
            buf.writeInt(magicCode0);
            buf.writeLong(capacity1);
            buf.writeLong(trimOffset2.get());
            buf.writeLong(lastWriteTimestamp3);
            buf.writeLong(slidingWindowNextWriteOffset4.get());
            buf.writeLong(slidingWindowStartOffset5.get());
            buf.writeLong(slidingWindowMaxLength6.get());
            buf.writeInt(shutdownType7.getCode());

            return buf;
        }

        ByteBuf marshal() {
            ByteBuf buf = marshalHeaderExceptCRC();
            this.crc8 = WALUtil.crc32(buf, WAL_HEADER_WITHOUT_CRC_SIZE);
            buf.writeInt(crc8);
            return buf;
        }
    }

    public static class BlockWALServiceBuilder {
        private final String blockDevicePath;
        private long blockDeviceCapacityWant;
        private int flushHeaderIntervalSeconds = 10;
        private int ioThreadNums = 8;
        private long slidingWindowInitialSize = 1 << 20;
        private long slidingWindowUpperLimit = 512 << 20;
        private long slidingWindowScaleUnit = 4 << 20;
        private long blockSoftLimit = 1 << 17; // 128KiB

        BlockWALServiceBuilder(String blockDevicePath, long capacity) {
            this.blockDevicePath = blockDevicePath;
            this.blockDeviceCapacityWant = capacity;
        }

        public BlockWALServiceBuilder config(Config config) {
            return this
                    .flushHeaderIntervalSeconds(config.s3WALHeaderFlushIntervalSeconds())
                    .ioThreadNums(config.s3WALThread())
                    .slidingWindowInitialSize(config.s3WALWindowInitial())
                    .slidingWindowScaleUnit(config.s3WALWindowIncrement())
                    .slidingWindowUpperLimit(config.s3WALWindowMax())
                    .blockSoftLimit(config.s3WALBlockSoftLimit());
        }

        public BlockWALServiceBuilder flushHeaderIntervalSeconds(int flushHeaderIntervalSeconds) {
            this.flushHeaderIntervalSeconds = flushHeaderIntervalSeconds;
            return this;
        }

        public BlockWALServiceBuilder ioThreadNums(int ioThreadNums) {
            this.ioThreadNums = ioThreadNums;
            return this;
        }

        public BlockWALServiceBuilder slidingWindowInitialSize(long slidingWindowInitialSize) {
            this.slidingWindowInitialSize = slidingWindowInitialSize;
            return this;
        }

        public BlockWALServiceBuilder slidingWindowUpperLimit(long slidingWindowUpperLimit) {
            this.slidingWindowUpperLimit = slidingWindowUpperLimit;
            return this;
        }

        public BlockWALServiceBuilder slidingWindowScaleUnit(long slidingWindowScaleUnit) {
            this.slidingWindowScaleUnit = slidingWindowScaleUnit;
            return this;
        }

        public BlockWALServiceBuilder blockSoftLimit(long blockSoftLimit) {
            this.blockSoftLimit = blockSoftLimit;
            return this;
        }

        public BlockWALService build() {
            BlockWALService blockWALService = new BlockWALService();

            // make blockDeviceCapacityWant align to BLOCK_SIZE
            blockDeviceCapacityWant = blockDeviceCapacityWant / WALUtil.BLOCK_SIZE * WALUtil.BLOCK_SIZE;

            // make sure window size is less than capacity
            slidingWindowInitialSize = Math.min(slidingWindowInitialSize, blockDeviceCapacityWant - WAL_HEADER_TOTAL_CAPACITY);
            slidingWindowUpperLimit = Math.min(slidingWindowUpperLimit, blockDeviceCapacityWant - WAL_HEADER_TOTAL_CAPACITY);

            blockWALService.walHeaderFlushIntervalSeconds = flushHeaderIntervalSeconds;
            blockWALService.initialWindowSize = slidingWindowInitialSize;

            blockWALService.walChannel = WALChannel.builder(blockDevicePath, blockDeviceCapacityWant).build();

            blockWALService.slidingWindowService = new SlidingWindowService(
                    blockWALService.walChannel,
                    ioThreadNums,
                    slidingWindowUpperLimit,
                    slidingWindowScaleUnit,
                    blockSoftLimit,
                    blockWALService.flusher()
            );

            LOGGER.info("build BlockWALService: {}", this);

            return blockWALService;
        }

        @Override
        public String toString() {
            return "BlockWALServiceBuilder{"
                    + "blockDevicePath='" + blockDevicePath
                    + ", blockDeviceCapacityWant=" + blockDeviceCapacityWant
                    + ", flushHeaderIntervalSeconds=" + flushHeaderIntervalSeconds
                    + ", ioThreadNums=" + ioThreadNums
                    + ", slidingWindowInitialSize=" + slidingWindowInitialSize
                    + ", slidingWindowUpperLimit=" + slidingWindowUpperLimit
                    + ", slidingWindowScaleUnit=" + slidingWindowScaleUnit
                    + ", blockSoftLimit=" + blockSoftLimit
                    + '}';
        }
    }

    record AppendResultImpl(long recordOffset, CompletableFuture<CallbackResult> future) implements AppendResult {

        @Override
        public String toString() {
            return "AppendResultImpl{" + "recordOffset=" + recordOffset + '}';
        }
    }

    record RecoverResultImpl(ByteBuf record, long recordOffset) implements RecoverResult {

        @Override
        public String toString() {
            return "RecoverResultImpl{"
                    + "record=" + record
                    + ", recordOffset=" + recordOffset
                    + '}';
        }
    }

    static class ReadRecordException extends Exception {
        long jumpNextRecoverOffset;

        public ReadRecordException(long offset, String message) {
            super(message);
            this.jumpNextRecoverOffset = offset;
        }

        public long getJumpNextRecoverOffset() {
            return jumpNextRecoverOffset;
        }
    }

    static class UnmarshalException extends Exception {
        public UnmarshalException(String message) {
            super(message);
        }
    }

    class RecoverIterator implements Iterator<RecoverResult> {
        private long nextRecoverOffset;
        private long skipRecordAtOffset = -1;
        private RecoverResult next;

        public RecoverIterator(long nextRecoverOffset) {
            this.nextRecoverOffset = nextRecoverOffset;
        }

        public RecoverIterator(long nextRecoverOffset, long skipRecordAtOffset) {
            this.nextRecoverOffset = nextRecoverOffset;
            this.skipRecordAtOffset = skipRecordAtOffset;
        }

        @Override
        public boolean hasNext() {
            return tryReadNextRecord();
        }

        @Override
        public RecoverResult next() {
            if (!tryReadNextRecord()) {
                throw new NoSuchElementException();
            }

            RecoverResult rst = next;
            this.next = null;
            return rst;
        }

        /**
         * Try to read next record.
         *
         * @return true if read success, false if no more record. {@link #next} will be null if and only if return false.
         */
        private boolean tryReadNextRecord() {
            if (next != null) {
                return true;
            }
            do {
                try {
                    boolean skip = nextRecoverOffset == skipRecordAtOffset;
                    ByteBuf nextRecordBody = readRecord(walHeaderCoreData.recordSectionCapacity(), nextRecoverOffset);
                    RecoverResultImpl recoverResult = new RecoverResultImpl(nextRecordBody, nextRecoverOffset);
                    nextRecoverOffset += RECORD_HEADER_SIZE + nextRecordBody.readableBytes();
                    if (skip) {
                        nextRecordBody.release();
                        continue;
                    }
                    next = recoverResult;
                    return true;
                } catch (ReadRecordException e) {
                    nextRecoverOffset = e.getJumpNextRecoverOffset();
                }
            } while (nextRecoverOffset < walHeaderCoreData.getSlidingWindowNextWriteOffset());
            return false;
        }
    }
}
