/**
 * Copyright (c) Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.segmentstore.storage.chunklayer;

import com.google.common.annotations.Beta;
import com.google.common.base.Preconditions;
import io.pravega.common.Exceptions;
import io.pravega.common.LoggerHelpers;
import io.pravega.common.Timer;
import io.pravega.common.concurrent.MultiKeySequentialProcessor;
import io.pravega.common.util.ImmutableDate;
import io.pravega.segmentstore.contracts.SegmentProperties;
import io.pravega.segmentstore.contracts.StreamSegmentExistsException;
import io.pravega.segmentstore.contracts.StreamSegmentInformation;
import io.pravega.segmentstore.contracts.StreamSegmentNotExistsException;
import io.pravega.segmentstore.contracts.StreamSegmentSealedException;
import io.pravega.segmentstore.storage.SegmentHandle;
import io.pravega.segmentstore.storage.SegmentRollingPolicy;
import io.pravega.segmentstore.storage.Storage;
import io.pravega.segmentstore.storage.StorageNotPrimaryException;
import io.pravega.segmentstore.storage.metadata.ChunkMetadata;
import io.pravega.segmentstore.storage.metadata.ChunkMetadataStore;
import io.pravega.segmentstore.storage.metadata.MetadataTransaction;
import io.pravega.segmentstore.storage.metadata.SegmentMetadata;
import io.pravega.segmentstore.storage.metadata.StorageMetadataWritesFencedOutException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import javax.annotation.concurrent.GuardedBy;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static io.pravega.segmentstore.storage.chunklayer.ChunkStorageMetrics.SLTS_CREATE_COUNT;
import static io.pravega.segmentstore.storage.chunklayer.ChunkStorageMetrics.SLTS_CREATE_LATENCY;
import static io.pravega.segmentstore.storage.chunklayer.ChunkStorageMetrics.SLTS_DELETE_COUNT;
import static io.pravega.segmentstore.storage.chunklayer.ChunkStorageMetrics.SLTS_DELETE_LATENCY;

/**
 * Implements storage for segments using {@link ChunkStorage} and {@link ChunkMetadataStore}.
 * The metadata about the segments is stored in metadataStore using two types of records {@link SegmentMetadata} and {@link ChunkMetadata}.
 * Any changes to layout must be made inside a {@link MetadataTransaction} which will atomically change the records upon
 * {@link MetadataTransaction#commit()}.
 * Detailed design is documented here https://github.com/pravega/pravega/wiki/PDP-34:-Simplified-Tier-2
 */
@Slf4j
@Beta
public class ChunkedSegmentStorage implements Storage {
    /**
     * Configuration options for this ChunkedSegmentStorage instance.
     */
    @Getter
    private final ChunkedSegmentStorageConfig config;

    /**
     * Metadata store containing all storage data.
     * Initialized by segment container via {@link ChunkedSegmentStorage#bootstrap()}.
     */
    @Getter
    private final ChunkMetadataStore metadataStore;

    /**
     * Underlying {@link ChunkStorage} to use to read and write data.
     */
    @Getter
    private final ChunkStorage chunkStorage;

    /**
     * Storage executor object.
     */
    @Getter
    private final Executor executor;

    /**
     * Tracks whether this instance is closed or not.
     */
    private final AtomicBoolean closed;

    /**
     * Current epoch of the {@link Storage} instance.
     * Initialized by segment container via {@link ChunkedSegmentStorage#initialize(long)}.
     */
    @Getter
    private volatile long epoch;

    /**
     * Id of the current Container.
     * Initialized by segment container via {@link ChunkedSegmentStorage#bootstrap()}.
     */
    @Getter
    private final int containerId;

    /**
     * {@link SystemJournal} that logs all changes to system segment layout so that they can be are used during system bootstrap.
     */
    @Getter
    private final SystemJournal systemJournal;

    /**
     * {@link ReadIndexCache} that has index of chunks by start offset
     */
    @Getter
    private final ReadIndexCache readIndexCache;

    /**
     * List of garbage chunks.
     */
    @Getter
    private final List<String> garbageChunks = new ArrayList<>();

    /**
     * Prefix string to use for logging.
     */
    @Getter
    private String logPrefix;

    /**
     * Instance of {@link MultiKeySequentialProcessor}.
     */
    private final MultiKeySequentialProcessor<String> taskProcessor;

    @GuardedBy("activeSegments")
    private final HashSet<String> activeRequests = new HashSet<>();

    /**
     * Creates a new instance of the ChunkedSegmentStorage class.
     *
     * @param containerId   container id.
     * @param chunkStorage  ChunkStorage instance.
     * @param metadataStore Metadata store.
     * @param executor      An Executor for async operations.
     * @param config        Configuration options for this ChunkedSegmentStorage instance.
     */
    public ChunkedSegmentStorage(int containerId, ChunkStorage chunkStorage, ChunkMetadataStore metadataStore, Executor executor, ChunkedSegmentStorageConfig config) {
        this.containerId = containerId;
        this.config = Preconditions.checkNotNull(config, "config");
        this.chunkStorage = Preconditions.checkNotNull(chunkStorage, "chunkStorage");
        this.metadataStore = Preconditions.checkNotNull(metadataStore, "metadataStore");
        this.executor = Preconditions.checkNotNull(executor, "executor");
        this.readIndexCache = new ReadIndexCache(config.getMaxIndexedSegments(),
                config.getMaxIndexedChunksPerSegment(),
                config.getMaxIndexedChunks());
        this.systemJournal = new SystemJournal(containerId,
                chunkStorage,
                metadataStore,
                config);
        this.taskProcessor = new MultiKeySequentialProcessor<>(this.executor);
        this.closed = new AtomicBoolean(false);
    }

    /**
     * Initializes the ChunkedSegmentStorage and bootstrap the metadata about storage metadata segments by reading and processing the journal.
     *
     * @throws Exception In case of any errors.
     */
    public CompletableFuture<Void> bootstrap() throws Exception {

        this.logPrefix = String.format("ChunkedSegmentStorage[%d]", containerId);

        // Now bootstrap
        log.debug("{} STORAGE BOOT: Started.", logPrefix);
        return this.systemJournal.bootstrap(epoch).thenRunAsync(() -> log.debug("{} STORAGE BOOT: Ended.", logPrefix), executor);
    }

    @Override
    public void initialize(long containerEpoch) {
        this.epoch = containerEpoch;
    }

    @Override
    public CompletableFuture<SegmentHandle> openWrite(String streamSegmentName) {
        checkInitialized();
        return executeSerialized(() -> {
            val traceId = LoggerHelpers.traceEnter(log, "openWrite", streamSegmentName);
            Preconditions.checkNotNull(streamSegmentName, "streamSegmentName");
            return tryWith(metadataStore.beginTransaction(false, streamSegmentName),
                    txn -> txn.get(streamSegmentName)
                            .thenComposeAsync(storageMetadata -> {
                                val segmentMetadata = (SegmentMetadata) storageMetadata;
                                checkSegmentExists(streamSegmentName, segmentMetadata);
                                segmentMetadata.checkInvariants();
                                // This segment was created by an older segment store. Need to start a fresh new chunk.
                                final CompletableFuture<Void> f;
                                if (segmentMetadata.getOwnerEpoch() < this.epoch) {
                                    log.debug("{} openWrite - Segment needs ownership change - segment={}.", logPrefix, segmentMetadata.getName());
                                    f = claimOwnership(txn, segmentMetadata)
                                            .exceptionally(e -> {
                                                val ex = Exceptions.unwrap(e);
                                                if (ex instanceof StorageMetadataWritesFencedOutException) {
                                                    throw new CompletionException(new StorageNotPrimaryException(streamSegmentName, ex));
                                                }
                                                throw new CompletionException(ex);
                                            });
                                } else {
                                    f = CompletableFuture.completedFuture(null);
                                }
                                return f.thenApplyAsync(v -> {
                                    // If created by newer instance then abort.
                                    checkOwnership(streamSegmentName, segmentMetadata);

                                    // This instance is the owner, return a handle.
                                    val retValue = SegmentStorageHandle.writeHandle(streamSegmentName);
                                    LoggerHelpers.traceLeave(log, "openWrite", traceId, retValue);
                                    return retValue;
                                }, executor);
                            }, executor),
                    executor);
        }, streamSegmentName);
    }

    /**
     * Checks ownership and adjusts the length of the segment if required.
     *
     * @param txn             Active {@link MetadataTransaction}.
     * @param segmentMetadata {@link SegmentMetadata} for the segment to change ownership for.
     *                        throws ChunkStorageException    In case of any chunk storage related errors.
     *                        throws StorageMetadataException In case of any chunk metadata store related errors.
     */
    private CompletableFuture<Void> claimOwnership(MetadataTransaction txn, SegmentMetadata segmentMetadata) {
        // Get the last chunk
        val lastChunkName = segmentMetadata.getLastChunk();
        final CompletableFuture<Boolean> f;
        if (null != lastChunkName) {
            f = txn.get(lastChunkName)
                    .thenComposeAsync(storageMetadata -> {
                        val lastChunk = (ChunkMetadata) storageMetadata;
                        Preconditions.checkState(null != lastChunk, "last chunk metadata must not be null.");
                        Preconditions.checkState(null != lastChunk.getName(), "Name of last chunk must not be null.");
                        log.debug("{} claimOwnership - current last chunk - segment={}, last chunk={}, Length={}.",
                                logPrefix,
                                segmentMetadata.getName(),
                                lastChunk.getName(),
                                lastChunk.getLength());
                        return chunkStorage.getInfo(lastChunkName)
                                .thenComposeAsync(chunkInfo -> {
                                    Preconditions.checkState(chunkInfo != null, "chunkInfo for last chunk must not be null.");
                                    Preconditions.checkState(lastChunk != null, "last chunk metadata must not be null.");
                                    // Adjust its length;
                                    if (chunkInfo.getLength() != lastChunk.getLength()) {
                                        Preconditions.checkState(chunkInfo.getLength() > lastChunk.getLength(),
                                                "Length of last chunk on LTS must be greater than what is in metadata.");
                                        // Whatever length you see right now is the final "sealed" length of the last chunk.
                                        lastChunk.setLength(chunkInfo.getLength());
                                        segmentMetadata.setLength(segmentMetadata.getLastChunkStartOffset() + lastChunk.getLength());
                                        txn.update(lastChunk);
                                        log.debug("{} claimOwnership - Length of last chunk adjusted - segment={}, last chunk={}, Length={}.",
                                                logPrefix,
                                                segmentMetadata.getName(),
                                                lastChunk.getName(),
                                                chunkInfo.getLength());
                                    }
                                    return CompletableFuture.completedFuture(true);
                                }, executor)
                                .exceptionally(e -> {
                                    val ex = Exceptions.unwrap(e);
                                    if (ex instanceof ChunkNotFoundException) {
                                        // This probably means that this instance is fenced out and newer instance truncated this segment.
                                        // Try a commit of unmodified data to fail fast.
                                        log.debug("{} claimOwnership - Last chunk was missing, failing fast - segment={}, last chunk={}.",
                                                logPrefix,
                                                segmentMetadata.getName(),
                                                lastChunk.getName());
                                        txn.update(segmentMetadata);
                                        return false;
                                    }
                                    throw new CompletionException(ex);
                                });
                    }, executor);
        } else {
            f = CompletableFuture.completedFuture(true);
        }

        return f.thenComposeAsync(shouldChange -> {
            // Claim ownership.
            // This is safe because the previous instance is definitely not an owner anymore. (even if this instance is no more owner)
            // If this instance is no more owner, then transaction commit will fail.So it is still safe.
            if (shouldChange) {
                segmentMetadata.setOwnerEpoch(this.epoch);
                segmentMetadata.setOwnershipChanged(true);
            }
            // Update and commit
            // If This instance is fenced this update will fail.
            txn.update(segmentMetadata);
            return txn.commit();
        }, executor);
    }

    @Override
    public CompletableFuture<SegmentHandle> create(String streamSegmentName, SegmentRollingPolicy rollingPolicy, Duration timeout) {
        checkInitialized();
        return executeSerialized(() -> {
            val traceId = LoggerHelpers.traceEnter(log, "create", streamSegmentName, rollingPolicy);
            val timer = new Timer();

            return tryWith(metadataStore.beginTransaction(false, streamSegmentName), txn -> {
                // Retrieve metadata and make sure it does not exist.
                return txn.get(streamSegmentName)
                        .thenComposeAsync(storageMetadata -> {
                            val oldSegmentMetadata = (SegmentMetadata) storageMetadata;
                            if (null != oldSegmentMetadata) {
                                throw new CompletionException(new StreamSegmentExistsException(streamSegmentName));
                            }

                            // Create a new record.
                            val newSegmentMetadata = SegmentMetadata.builder()
                                    .name(streamSegmentName)
                                    .maxRollinglength(rollingPolicy.getMaxLength() == 0 ? SegmentRollingPolicy.NO_ROLLING.getMaxLength() : rollingPolicy.getMaxLength())
                                    .ownerEpoch(this.epoch)
                                    .build();

                            newSegmentMetadata.setActive(true);
                            txn.create(newSegmentMetadata);
                            // commit.
                            return txn.commit()
                                    .thenApplyAsync(v -> {
                                        val retValue = SegmentStorageHandle.writeHandle(streamSegmentName);
                                        Duration elapsed = timer.getElapsed();
                                        SLTS_CREATE_LATENCY.reportSuccessEvent(elapsed);
                                        SLTS_CREATE_COUNT.inc();
                                        log.debug("{} create - segment={}, rollingPolicy={}, latency={}.", logPrefix, streamSegmentName, rollingPolicy, elapsed.toMillis());
                                        LoggerHelpers.traceLeave(log, "create", traceId, retValue);
                                        return retValue;
                                    }, executor)
                                    .handleAsync((v, e) -> {
                                        handleException(streamSegmentName, e);
                                        return v;
                                    }, executor);
                        }, executor);
            }, executor);
        }, streamSegmentName);
    }

    private void handleException(String streamSegmentName, Throwable e) {
        if (null != e) {
            val ex = Exceptions.unwrap(e);
            if (ex instanceof StorageMetadataWritesFencedOutException) {
                throw new CompletionException(new StorageNotPrimaryException(streamSegmentName, ex));
            }
            throw new CompletionException(ex);
        }
    }

    @Override
    public CompletableFuture<Void> write(SegmentHandle handle, long offset, InputStream data, int length, Duration timeout) {
        checkInitialized();
        if (null == handle) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("handle"));
        }
        if (null == handle.getSegmentName()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("handle.segmentName"));
        }
        return executeSerialized(new WriteOperation(this, handle, offset, data, length), handle.getSegmentName());
    }

    /**
     * Gets whether given segment is a critical storage system segment.
     *
     * @param segmentMetadata Metadata for the segment.
     * @return True if this is a storage system segment.
     */
    boolean isStorageSystemSegment(SegmentMetadata segmentMetadata) {
        return null != systemJournal && segmentMetadata.isStorageSystemSegment();
    }

    /**
     * Delete the garbage chunks.
     *
     * @param chunksToDelete List of chunks to delete.
     */
    CompletableFuture<Void> collectGarbage(Collection<String> chunksToDelete) {
        CompletableFuture[] futures = new CompletableFuture[chunksToDelete.size()];
        int i = 0;
        for (val chunkToDelete : chunksToDelete) {
            futures[i++] = chunkStorage.openWrite(chunkToDelete)
                    .thenComposeAsync(chunkStorage::delete, executor)
                    .thenRunAsync(() -> log.debug("{} collectGarbage - deleted chunk={}.", logPrefix, chunkToDelete), executor)
                    .exceptionally(e -> {
                        val ex = Exceptions.unwrap(e);
                        if (ex instanceof ChunkNotFoundException) {
                            log.debug("{} collectGarbage - Could not delete garbage chunk {}.", logPrefix, chunkToDelete);
                        } else {
                            log.warn("{} collectGarbage - Could not delete garbage chunk {}.", logPrefix, chunkToDelete);
                            // Add it to garbage chunks.
                            synchronized (garbageChunks) {
                                garbageChunks.add(chunkToDelete);
                            }
                        }
                        return null;
                    });
        }
        return CompletableFuture.allOf(futures);
    }

    @Override
    public CompletableFuture<Void> seal(SegmentHandle handle, Duration timeout) {
        checkInitialized();
        return executeSerialized(() -> {
            val traceId = LoggerHelpers.traceEnter(log, "seal", handle);
            Preconditions.checkNotNull(handle, "handle");
            String streamSegmentName = handle.getSegmentName();
            Preconditions.checkNotNull(streamSegmentName, "streamSegmentName");
            Preconditions.checkArgument(!handle.isReadOnly(), "handle");

            return tryWith(metadataStore.beginTransaction(false, handle.getSegmentName()), txn ->
                    txn.get(streamSegmentName)
                            .thenComposeAsync(storageMetadata -> {
                                val segmentMetadata = (SegmentMetadata) storageMetadata;
                                // Validate preconditions.
                                checkSegmentExists(streamSegmentName, segmentMetadata);
                                checkOwnership(streamSegmentName, segmentMetadata);

                                // seal if it is not already sealed.
                                if (!segmentMetadata.isSealed()) {
                                    segmentMetadata.setSealed(true);
                                    txn.update(segmentMetadata);
                                    return txn.commit();
                                } else {
                                    return CompletableFuture.completedFuture(null);
                                }
                            }, executor)
                            .thenRunAsync(() -> {
                                log.debug("{} seal - segment={}.", logPrefix, handle.getSegmentName());
                                LoggerHelpers.traceLeave(log, "seal", traceId, handle);
                            }, executor)
                            .exceptionally(e -> {
                                val ex = Exceptions.unwrap(e);
                                if (ex instanceof StorageMetadataWritesFencedOutException) {
                                    throw new CompletionException(new StorageNotPrimaryException(streamSegmentName, ex));
                                }
                                throw new CompletionException(ex);
                            }), executor);
        }, handle.getSegmentName());
    }

    @Override
    public CompletableFuture<Void> concat(SegmentHandle targetHandle, long offset, String sourceSegment, Duration timeout) {
        checkInitialized();
        if (null == targetHandle) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("handle"));
        }
        if (null == targetHandle.getSegmentName()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("handle.segmentName"));
        }
        if (null == sourceSegment) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("sourceSegment"));
        }

        return executeSerialized(new ConcatOperation(this, targetHandle, offset, sourceSegment), targetHandle.getSegmentName(), sourceSegment);
    }

    boolean shouldAppend() {
        return chunkStorage.supportsAppend() && config.isAppendEnabled();
    }

    /**
     * Defragments the list of chunks for a given segment.
     * It finds eligible consecutive chunks that can be merged together.
     * The sublist such eligible chunks is replaced with single new chunk record corresponding to new large chunk.
     * Conceptually this is like deleting nodes from middle of the list of chunks.
     *
     * @param txn             Active {@link MetadataTransaction}.
     * @param segmentMetadata {@link SegmentMetadata} for the segment to defrag.
     * @param startChunkName  Name of the first chunk to start defragmentation.
     * @param lastChunkName   Name of the last chunk before which to stop defragmentation. (last chunk is not concatenated).
     * @param chunksToDelete  List of chunks to which names of chunks to be deleted are added. It is the responsibility
     *                        of caller to garbage collect these chunks.
     *                        throws ChunkStorageException    In case of any chunk storage related errors.
     *                        throws StorageMetadataException In case of any chunk metadata store related errors.
     */
    public CompletableFuture<Void> defrag(MetadataTransaction txn, SegmentMetadata segmentMetadata,
                                           String startChunkName,
                                           String lastChunkName,
                                           ArrayList<String> chunksToDelete) {
        return new DefragmentOperation(this, txn, segmentMetadata, startChunkName, lastChunkName, chunksToDelete).call();
    }

    @Override
    public CompletableFuture<Void> delete(SegmentHandle handle, Duration timeout) {
        checkInitialized();
        return executeSerialized(() -> {
            val traceId = LoggerHelpers.traceEnter(log, "delete", handle);
            val timer = new Timer();

            val streamSegmentName = handle.getSegmentName();
            return tryWith(metadataStore.beginTransaction(false, streamSegmentName), txn -> txn.get(streamSegmentName)
                    .thenComposeAsync(storageMetadata -> {
                        val segmentMetadata = (SegmentMetadata) storageMetadata;
                        // Check preconditions
                        checkSegmentExists(streamSegmentName, segmentMetadata);
                        checkOwnership(streamSegmentName, segmentMetadata);

                        segmentMetadata.setActive(false);

                        // Delete chunks
                        val chunksToDelete = new ArrayList<String>();
                        return new ChunkIterator(this, txn, segmentMetadata)
                                .forEach((metadata, name) -> {
                                    txn.delete(name);
                                    chunksToDelete.add(name);
                                })
                                .thenRunAsync(() -> txn.delete(streamSegmentName), executor)
                                .thenComposeAsync(v ->
                                        txn.commit()
                                                .thenComposeAsync(vv -> {
                                                    // Collect garbage.
                                                    return collectGarbage(chunksToDelete);
                                                }, executor)
                                                .thenRunAsync(() -> {
                                                    // Update the read index.
                                                    readIndexCache.remove(streamSegmentName);

                                                    val elapsed = timer.getElapsed();
                                                    SLTS_DELETE_LATENCY.reportSuccessEvent(elapsed);
                                                    SLTS_DELETE_COUNT.inc();
                                                    log.debug("{} delete - segment={}, latency={}.", logPrefix, handle.getSegmentName(), elapsed.toMillis());
                                                    LoggerHelpers.traceLeave(log, "delete", traceId, handle);
                                                }, executor)
                                                .exceptionally(e -> {
                                                    val ex = Exceptions.unwrap(e);
                                                    if (ex instanceof StorageMetadataWritesFencedOutException) {
                                                        throw new CompletionException(new StorageNotPrimaryException(streamSegmentName, ex));
                                                    }
                                                    throw new CompletionException(ex);
                                                }), executor);
                    }, executor), executor);
        }, handle.getSegmentName());
    }

    @Override
    public CompletableFuture<Void> truncate(SegmentHandle handle, long offset, Duration timeout) {
        checkInitialized();
        if (null == handle) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("handle"));
        }
        if (null == handle.getSegmentName()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("handle.segmentName"));
        }

        return executeSerialized(new TruncateOperation(this, handle, offset), handle.getSegmentName());
    }

    @Override
    public boolean supportsTruncation() {
        return true;
    }

    @Override
    public Iterator<SegmentProperties> listSegments() {
        throw new UnsupportedOperationException("listSegments is not yet supported");
    }

    @Override
    public CompletableFuture<SegmentHandle> openRead(String streamSegmentName) {
        checkInitialized();
        return executeSerialized(() -> {
            val traceId = LoggerHelpers.traceEnter(log, "openRead", streamSegmentName);
            // Validate preconditions and return handle.
            Preconditions.checkNotNull(streamSegmentName, "streamSegmentName");
            return tryWith(metadataStore.beginTransaction(false, streamSegmentName), txn ->
                            txn.get(streamSegmentName).thenComposeAsync(storageMetadata -> {
                                val segmentMetadata = (SegmentMetadata) storageMetadata;
                                checkSegmentExists(streamSegmentName, segmentMetadata);
                                segmentMetadata.checkInvariants();
                                // This segment was created by an older segment store. Then claim ownership and adjust length.
                                final CompletableFuture<Void> f;
                                if (segmentMetadata.getOwnerEpoch() < this.epoch) {
                                    log.debug("{} openRead - Segment needs ownership change. segment={}.", logPrefix, segmentMetadata.getName());
                                    // In case of a fail-over, length recorded in metadata will be lagging behind its actual length in the storage.
                                    // This can happen with lazy commits that were still not committed at the time of fail-over.
                                    f = claimOwnership(txn, segmentMetadata)
                                            .exceptionally(e -> {
                                                val ex = Exceptions.unwrap(e);
                                                if (ex instanceof StorageMetadataWritesFencedOutException) {
                                                    throw new CompletionException(new StorageNotPrimaryException(streamSegmentName, ex));
                                                }
                                                throw new CompletionException(ex);
                                            });
                                } else {
                                    f = CompletableFuture.completedFuture(null);
                                }
                                return f.thenApplyAsync(v -> {
                                    val retValue = SegmentStorageHandle.readHandle(streamSegmentName);
                                    LoggerHelpers.traceLeave(log, "openRead", traceId, retValue);
                                    return retValue;
                                }, executor);
                            }, executor),
                    executor);
        }, streamSegmentName);
    }

    @Override
    public CompletableFuture<Integer> read(SegmentHandle handle, long offset, byte[] buffer, int bufferOffset, int length, Duration timeout) {
        checkInitialized();
        return executeSerialized(new ReadOperation(this, handle, offset, buffer, bufferOffset, length), handle.getSegmentName());
    }

    @Override
    public CompletableFuture<SegmentProperties> getStreamSegmentInfo(String streamSegmentName, Duration timeout) {
        checkInitialized();
        return executeSerialized(() -> {
            val traceId = LoggerHelpers.traceEnter(log, "getStreamSegmentInfo", streamSegmentName);
            Preconditions.checkNotNull(streamSegmentName, "streamSegmentName");
            return tryWith(metadataStore.beginTransaction(true, streamSegmentName), txn ->
                    txn.get(streamSegmentName)
                            .thenApplyAsync(storageMetadata -> {
                                SegmentMetadata segmentMetadata = (SegmentMetadata) storageMetadata;
                                if (null == segmentMetadata) {
                                    throw new CompletionException(new StreamSegmentNotExistsException(streamSegmentName));
                                }
                                segmentMetadata.checkInvariants();

                                val retValue = StreamSegmentInformation.builder()
                                        .name(streamSegmentName)
                                        .sealed(segmentMetadata.isSealed())
                                        .length(segmentMetadata.getLength())
                                        .startOffset(segmentMetadata.getStartOffset())
                                        .lastModified(new ImmutableDate(segmentMetadata.getLastModified()))
                                        .build();
                                LoggerHelpers.traceLeave(log, "getStreamSegmentInfo", traceId, retValue);
                                return retValue;
                            }, executor), executor);
        }, streamSegmentName);
    }

    @Override
    public CompletableFuture<Boolean> exists(String streamSegmentName, Duration timeout) {
        checkInitialized();
        return executeSerialized(() -> {
            val traceId = LoggerHelpers.traceEnter(log, "exists", streamSegmentName);
            Preconditions.checkNotNull(streamSegmentName, "streamSegmentName");
            return tryWith(metadataStore.beginTransaction(true, streamSegmentName),
                    txn -> txn.get(streamSegmentName)
                            .thenApplyAsync(storageMetadata -> {
                                SegmentMetadata segmentMetadata = (SegmentMetadata) storageMetadata;
                                val retValue = segmentMetadata != null && segmentMetadata.isActive();
                                LoggerHelpers.traceLeave(log, "exists", traceId, retValue);
                                return retValue;
                            }, executor),
                    executor);
        }, streamSegmentName);
    }

    @Override
    public void close() {
        try {
            if (null != this.metadataStore) {
                this.metadataStore.close();
            }
        } catch (Exception e) {
            log.warn("Error during close", e);
        }
        this.closed.set(true);
    }

    /**
     * Executes the given Callable asynchronously and returns a CompletableFuture that will be completed with the result.
     * The operations are serialized on the segmentNames provided.
     *
     * @param operation    The Callable to execute.
     * @param <R>       Return type of the operation.
     * @param segmentNames The names of the Segments involved in this operation (for sequencing purposes).
     * @return A CompletableFuture that, when completed, will contain the result of the operation.
     * If the operation failed, it will contain the cause of the failure.
     * */
    private <R> CompletableFuture<R> executeSerialized(Callable<CompletableFuture<R>> operation, String... segmentNames) {
        Exceptions.checkNotClosed(this.closed.get(), this);
        return this.taskProcessor.add(Arrays.asList(segmentNames), () -> executeExclusive(operation, segmentNames));
    }

    /**
     * Executes the given Callable asynchronously and exclusively.
     * It returns a CompletableFuture that will be completed with the result.
     * The operations are not allowed to be concurrent.
     *
     * @param operation    The Callable to execute.
     * @param <R>       Return type of the operation.
     * @param segmentNames The names of the Segments involved in this operation (for sequencing purposes).
     * @return A CompletableFuture that, when completed, will contain the result of the operation.
     * If the operation failed, it will contain the cause of the failure.
     * */
    private <R> CompletableFuture<R> executeExclusive(Callable<CompletableFuture<R>> operation, String... segmentNames) {
        val shouldRelease = new AtomicBoolean(false);
        acquire(segmentNames);
        shouldRelease.set(true);
        return CompletableFuture.completedFuture(null).thenComposeAsync(v -> {
            Exceptions.checkNotClosed(this.closed.get(), this);
            try {
                return operation.call();
            } catch (CompletionException e) {
                throw new CompletionException(Exceptions.unwrap(e));
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, this.executor)
        .whenCompleteAsync((v, e) -> {
            if (shouldRelease.get()) {
                release(segmentNames);
            }
        }, this.executor);
    }

    /**
     * Adds the given segments to the exclusion list.
     * If concurrent requests are detected then throws {@link ConcurrentModificationException}.
     *
     * @param segmentNames The names of the Segments involved in this operation (for sequencing purposes).
     */
    private void acquire(String... segmentNames) {
        synchronized (activeRequests) {
            for (String segmentName : segmentNames) {
                if (activeRequests.contains(segmentName)) {
                    log.error("{} Concurrent modifications for Segment={}", logPrefix, segmentName);
                    throw new ConcurrentModificationException(String.format("Concurrent modifications not allowed. Segment=%s", segmentName));
                }
            }
            // Now that we have validated, mark all keys as "locked".
            for (String segmentName : segmentNames) {
                activeRequests.add(segmentName);
            }
        }
    }

    /**
     * Removes the given segments from exclusion list.
     *
     * @param segmentNames The names of the Segments involved in this operation (for sequencing purposes).
     */
    private void release(String... segmentNames) {
        synchronized (activeRequests) {
            for (String segmentName : segmentNames) {
                activeRequests.remove(segmentName);
            }
        }
    }

    static <T extends AutoCloseable, R> CompletableFuture<R> tryWith(T closeable, Function<T, CompletableFuture<R>> function, Executor executor) {
        return function.apply(closeable)
                    .whenCompleteAsync((v, ex) -> {
                        try {
                            closeable.close();
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    }, executor);
    }

    final void checkSegmentExists(String streamSegmentName, SegmentMetadata segmentMetadata) {
        if (null == segmentMetadata || !segmentMetadata.isActive()) {
            throw new CompletionException(new StreamSegmentNotExistsException(streamSegmentName));
        }
    }

    final void checkOwnership(String streamSegmentName, SegmentMetadata segmentMetadata) {
        if (segmentMetadata.getOwnerEpoch() > this.epoch) {
            throw new CompletionException(new StorageNotPrimaryException(streamSegmentName));
        }
    }

    final void checkNotSealed(String streamSegmentName, SegmentMetadata segmentMetadata) {
        if (segmentMetadata.isSealed()) {
            throw new CompletionException(new StreamSegmentSealedException(streamSegmentName));
        }
    }

    private void checkInitialized() {
        Preconditions.checkState(null != this.metadataStore, "metadata store must not be null");
        Preconditions.checkState(0 != this.epoch, "epoch must not be zero");
        Preconditions.checkState(!closed.get(), "ChunkedSegmentStorage instance must not be closed");
    }
}