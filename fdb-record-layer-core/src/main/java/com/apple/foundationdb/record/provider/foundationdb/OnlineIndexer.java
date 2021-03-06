/*
 * OnlineIndexer.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2018 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.provider.foundationdb;

import com.apple.foundationdb.API;
import com.apple.foundationdb.FDBException;
import com.apple.foundationdb.Range;
import com.apple.foundationdb.Transaction;
import com.apple.foundationdb.async.AsyncIterator;
import com.apple.foundationdb.async.AsyncUtil;
import com.apple.foundationdb.async.MoreAsyncUtil;
import com.apple.foundationdb.async.RangeSet;
import com.apple.foundationdb.record.EndpointType;
import com.apple.foundationdb.record.ExecuteProperties;
import com.apple.foundationdb.record.IsolationLevel;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.RecordCoreStorageException;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordMetaData;
import com.apple.foundationdb.record.RecordMetaDataProvider;
import com.apple.foundationdb.record.ScanProperties;
import com.apple.foundationdb.record.TupleRange;
import com.apple.foundationdb.record.logging.KeyValueLogMessage;
import com.apple.foundationdb.record.logging.LogMessageKeys;
import com.apple.foundationdb.record.metadata.Index;
import com.apple.foundationdb.record.metadata.Key;
import com.apple.foundationdb.record.metadata.MetaDataException;
import com.apple.foundationdb.record.metadata.RecordType;
import com.apple.foundationdb.record.provider.common.RecordSerializer;
import com.apple.foundationdb.subspace.Subspace;
import com.apple.foundationdb.tuple.ByteArrayUtil2;
import com.apple.foundationdb.tuple.Tuple;
import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Builds an index online, i.e., concurrently with other database operations. In order to minimize
 * the impact that these operations have with other operations, this attempts to minimize the
 * priorities of its transactions. Additionally, it attempts to limit the amount of work it will
 * done in a fashion that will decrease as the number of failures for a given build attempt increases.
 *
 * <p>
 * As ranges of elements are rebuilt, the fact that the range has rebuilt is added to a {@link com.apple.foundationdb.async.RangeSet}
 * associated with the index being built. This {@link com.apple.foundationdb.async.RangeSet} is used to (a) coordinate work between
 * different builders that might be running on different machines to ensure that the same work isn't
 * duplicated and to (b) make sure that non-idempotent indexes (like <code>COUNT</code> or <code>SUM_LONG</code>)
 * don't update themselves (or fail to update themselves) incorrectly.
 * </p>
 *
 * <p>
 * Unlike many other features in the Record Layer core, this has a retry loop.
 * </p>
 *
 * <p>Build an index immediately in the current transaction:</p>
 * <pre><code>
 * try (OnlineIndexer indexBuilder = OnlineIndexer.forRecordStoreAndIndex(recordStore, "newIndex")) {
 *     indexBuilder.rebuildIndex(recordStore);
 * }
 * </code></pre>
 *
 * <p>Build an index synchronously in the multiple transactions:</p>
 * <pre><code>
 * try (OnlineIndexer indexBuilder = OnlineIndexer.forRecordStoreAndIndex(recordStore, "newIndex")) {
 *     indexBuilder.buildIndex();
 * }
 * </code></pre>
 */
@API(API.Status.UNSTABLE)
public class OnlineIndexer implements AutoCloseable {
    /**
     * Default number of records to attempt to run in a single transaction.
     */
    public static final int DEFAULT_LIMIT = 100;
    /**
     * Default limit to the number of records to attempt in a single second.
     */
    public static final int DEFAULT_RECORDS_PER_SECOND = 10_000;
    /**
     * Default number of times to retry a single range rebuild.
     */
    public static final int DEFAULT_MAX_RETRIES = 100;
    /**
     * Constant indicating that there should be no limit to some usually limited operation.
     */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    @Nonnull private static final byte[] START_BYTES = new byte[]{0x00};
    @Nonnull private static final byte[] END_BYTES = new byte[]{(byte)0xff};
    @Nonnull private static final Logger LOGGER = LoggerFactory.getLogger(OnlineIndexer.class);

    // These error codes represent a list of errors that can occur if there is too much work to be done
    // in a single transaction.
    private static final Set<Integer> lessenWorkCodes = new HashSet<>(Arrays.asList(1004, 1007, 1020, 1031, 2002, 2101));

    @Nonnull private final FDBDatabaseRunner runner;
    @Nonnull private final FDBRecordStore.Builder recordStoreBuilder;
    @Nonnull private final Index index;
    @Nonnull private final Collection<RecordType> recordTypes;
    @Nonnull private final TupleRange recordsRange;
    private int limit;  // Not final as may be adjusted when running.
    private final int maxRetries;
    private final int recordsPerSecond;

    protected OnlineIndexer(@Nonnull FDBDatabaseRunner runner,
                            @Nonnull FDBRecordStore.Builder recordStoreBuilder,
                            @Nonnull Index index, @Nonnull Collection<RecordType> recordTypes,
                            int limit, int maxRetries, int recordsPerSecond) {
        this.runner = runner;
        this.recordStoreBuilder = recordStoreBuilder;
        this.index = index;
        this.recordTypes = recordTypes;
        this.limit = limit;
        this.maxRetries = maxRetries;
        this.recordsPerSecond = recordsPerSecond;
        this.recordsRange = computeRecordsRange();
    }

    /**
     * This {@link Exception} can be thrown in the case that one calls one of the methods
     * that explicitly state that they are building an unbuilt range, i.e., a range of keys
     * that contains no keys which have yet been processed by the {@link OnlineIndexer}
     * during an index build.
     */
    @SuppressWarnings("serial")
    public static class RecordBuiltRangeException extends RecordCoreException {
        public RecordBuiltRangeException(@Nullable Tuple start, @Nullable Tuple end) {
            super("Range specified as unbuilt contained subranges that had already been built");
            addLogInfo(LogMessageKeys.RANGE_START, start);
            addLogInfo(LogMessageKeys.RANGE_END, end);
        }
    }

    private TupleRange computeRecordsRange() {
        Tuple low = null;
        Tuple high = null;
        for (RecordType recordType : recordTypes) {
            if (!recordType.primaryKeyHasRecordTypePrefix()) {
                // If any of the types to build for does not have a prefix, give up.
                return TupleRange.ALL;
            }
            Tuple prefix = Tuple.from(recordType.getRecordTypeKey());
            if (low == null) {
                low = high = prefix;
            } else {
                if (low.compareTo(prefix) > 0) {
                    low = prefix;
                }
                if (high.compareTo(prefix) < 0) {
                    high = prefix;
                }
            }
        }
        if (low == null) {
            return TupleRange.ALL;
        } else {
            // Both ends inclusive.
            return new TupleRange(low, high, EndpointType.RANGE_INCLUSIVE, EndpointType.RANGE_INCLUSIVE);
        }
    }

    // Finds the FDBException that ultimately caused some throwable or
    // null if there is none. This can be then used to determine, for
    // example, the error code associated with this FDBException.
    @Nullable
    private FDBException getFDBException(@Nullable Throwable e) {
        Throwable curr = e;
        while (curr != null) {
            if (curr instanceof FDBException) {
                return (FDBException)curr;
            } else {
                curr = curr.getCause();
            }
        }
        return null;
    }

    // Turn a (possibly null) key into its tuple representation.
    @Nullable
    private Tuple convertOrNull(@Nullable Key.Evaluated key) {
        return (key == null) ? null : key.toTuple();
    }

    // Turn a (possibly null) tuple into a (possibly null) byte array.
    @Nullable
    private byte[] packOrNull(@Nullable Tuple tuple) {
        return (tuple == null) ? null : tuple.pack();
    }

    @SuppressWarnings("squid:S1452")
    private CompletableFuture<FDBRecordStore> openRecordStore(@Nonnull FDBRecordContext context) {
        return recordStoreBuilder.copyBuilder().setContext(context).openAsync();
    }

    @Override
    public void close() {
        runner.close();
    }

    // This retry loop runs an operation on a record store. The reason that this retry loop exists
    // (despite the fact that FDBDatabase.runAsync exists and is (in fact) used by the logic here)
    // is that this will adjust the value of limit in the case that we encounter errors like transaction_too_large
    // that are not retriable but can be addressed by decreasing the amount of work that has to
    // be done in a single transaction. This allows the OnlineIndexer to respond to being given
    // a bad value for limit.
    @Nonnull
    @VisibleForTesting
    <R> CompletableFuture<R> runAsync(@Nonnull Function<FDBRecordStore, CompletableFuture<R>> function) {
        AtomicInteger tries = new AtomicInteger(0);
        CompletableFuture<R> ret = new CompletableFuture<>();
        AtomicLong toWait = new AtomicLong(FDBDatabaseFactory.instance().getInitialDelayMillis());

        AsyncUtil.whileTrue(() ->
                runner.runAsync(context -> {
                    // One difference here from your standard retry loop is that within this method, we set the
                    // priority to "batch" on all transactions in order to avoid other stepping on the toes of other work.
                    context.ensureActive().options().setPriorityBatch();
                    return openRecordStore(context).thenCompose(store -> {
                        if (!store.isIndexWriteOnly(index)) {
                            throw new RecordCoreStorageException("Attempted to build readable index",
                                    LogMessageKeys.INDEX_NAME, index.getName(),
                                    recordStoreBuilder.getSubspaceProvider().logKey(), recordStoreBuilder.getSubspaceProvider());
                        }
                        return function.apply(store);
                    });
                }).handle((value, e) -> {
                    if (e == null) {
                        ret.complete(value);
                        return AsyncUtil.READY_FALSE;
                    } else {
                        int currTries = tries.getAndIncrement();
                        if (currTries >= maxRetries) {
                            ret.completeExceptionally(runner.getDatabase().mapAsyncToSyncException(e));
                            return AsyncUtil.READY_FALSE;
                        }

                        FDBException fdbE = getFDBException(e);
                        if (fdbE == null) {
                            ret.completeExceptionally(runner.getDatabase().mapAsyncToSyncException(e));
                            return AsyncUtil.READY_FALSE;
                        } else {
                            if (lessenWorkCodes.contains(fdbE.getCode())) {
                                limit = Math.max(1, (3 * limit) / 4);
                                if (LOGGER.isInfoEnabled()) {
                                    LOGGER.info(KeyValueLogMessage.of("Lessening limit of online index build",
                                                    "indexName", index.getName(),
                                                    "indexVersion", index.getLastModifiedVersion(),
                                                    "error", fdbE.getMessage(),
                                                    "errorCode", fdbE.getCode(),
                                                    "limit", limit),
                                            fdbE);
                                }
                                long delay = (long)(Math.random() * toWait.get());
                                toWait.set(Math.min(delay * 2, FDBDatabaseFactory.instance().getMaxDelayMillis()));
                                return MoreAsyncUtil.delayedFuture(delay, TimeUnit.MILLISECONDS).thenApply(vignore3 -> true);
                            } else {
                                ret.completeExceptionally(runner.getDatabase().mapAsyncToSyncException(e));
                                return AsyncUtil.READY_FALSE;
                            }
                        }
                    }
                }).thenCompose(Function.identity()), runner.getExecutor())
                .whenComplete((vignore, e) -> {
                    if (e != null) {
                        ret.completeExceptionally(runner.getDatabase().mapAsyncToSyncException(e));
                    }
                });

        return ret;
    }

    // Builds the index for all of the keys within a given range. This does not update the range set
    // associated with this index, so it is really designed to be a helper for other methods.
    @Nonnull
    private CompletableFuture<Tuple> buildRangeOnly(@Nonnull FDBRecordStore store,
                                                    @Nullable Tuple start, @Nullable Tuple end,
                                                    boolean respectLimit) {
        return buildRangeOnly(store, TupleRange.between(start, end), respectLimit).thenApply(realEnd -> realEnd == null ? end : realEnd);
    }

    // TupleRange version of above.
    @Nonnull
    private CompletableFuture<Tuple> buildRangeOnly(@Nonnull FDBRecordStore store, @Nonnull TupleRange range, boolean respectLimit) {
        if (store.getRecordMetaData() != recordStoreBuilder.getMetaDataProvider().getRecordMetaData()) {
            throw new MetaDataException("Store does not have the same metadata");
        }
        final IndexMaintainer maintainer = store.getIndexMaintainer(index);
        final ExecuteProperties.Builder executeProperties = ExecuteProperties.newBuilder()
                .setIsolationLevel(IsolationLevel.SERIALIZABLE);
        if (respectLimit) {
            executeProperties.setReturnedRowLimit(limit);
        }
        final ScanProperties scanProperties = new ScanProperties(executeProperties.build());
        final RecordCursor<FDBStoredRecord<Message>> cursor = store.scanRecords(range, null, scanProperties);
        final AtomicBoolean empty = new AtomicBoolean(true);
        final FDBStoreTimer timer = runner.getTimer();

        // Note: This runs all of the updates in serial in order to not invoke a race condition
        // in the rank code that was causing incorrect results. If everything were thread safe,
        // a larger pipeline size would be possible.
        return cursor.forEachAsync(rec -> {
            empty.set(false);
            if (timer != null) {
                timer.increment(FDBStoreTimer.Counts.ONLINE_INDEX_BUILDER_RECORDS_SCANNED);
            }
            if (recordTypes.contains(rec.getRecordType())) {
                if (timer != null) {
                    timer.increment(FDBStoreTimer.Counts.ONLINE_INDEX_BUILDER_RECORDS_INDEXED);
                }
                return maintainer.update(null, rec);
            } else {
                return AsyncUtil.DONE;
            }
        }, 1).thenCompose(vignore -> {
            byte[] nextCont = empty.get() ? null : cursor.getContinuation();
            if (nextCont == null) {
                return CompletableFuture.completedFuture(null);
            } else {
                // Get the next record and return its primary key.
                executeProperties.setReturnedRowLimit(1);
                final ScanProperties scanProperties1 = new ScanProperties(executeProperties.build());
                RecordCursor<FDBStoredRecord<Message>> nextCursor = store.scanRecords(range, nextCont, scanProperties1);
                return nextCursor.onHasNext().thenApply(hasNext -> {
                    if (hasNext) {
                        FDBStoredRecord<Message> rec = nextCursor.next();
                        return rec.getPrimaryKey();
                    } else {
                        return null;
                    }
                });
            }
        });
    }

    // Builds a range within a single transaction. It will look for the missing ranges within the given range and build those while
    // updating the range set.
    @Nonnull
    private CompletableFuture<Void> buildRange(@Nonnull FDBRecordStore store, @Nullable Tuple start, @Nullable Tuple end) {
        RangeSet rangeSet = new RangeSet(store.indexRangeSubspace(index));
        AsyncIterator<Range> ranges = rangeSet.missingRanges(store.ensureContextActive(), packOrNull(start), packOrNull(end)).iterator();
        return ranges.onHasNext().thenCompose(hasAny -> {
            if (hasAny) {
                return AsyncUtil.whileTrue(() -> {
                    Range range = ranges.next();
                    Tuple rangeStart = Arrays.equals(range.begin, START_BYTES) ? null : Tuple.fromBytes(range.begin);
                    Tuple rangeEnd = Arrays.equals(range.end, END_BYTES) ? null : Tuple.fromBytes(range.end);
                    return CompletableFuture.allOf(
                            // All of the requested range without limit.
                            // In practice, this method works because it is only called for the endpoint ranges, which are empty and
                            // one long, respectively.
                            buildRangeOnly(store, rangeStart, rangeEnd, false),
                            rangeSet.insertRange(store.ensureContextActive(), range, true)
                    ).thenCompose(vignore -> ranges.onHasNext());
                }, store.getExecutor());
            } else {
                return AsyncUtil.DONE;
            }
        });
    }

    /**
     * Builds (transactionally) the index by adding records with primary keys within the given range.
     * This will look for gaps of keys within the given range that haven't yet been rebuilt and then
     * rebuild only those ranges. As a result, if this method is called twice, the first time, it will
     * build whatever needs to be built, and then the second time, it will notice that there are no ranges
     * that need to be built, so it will do nothing. In this way, it is idempotent and thus safe to
     * use in retry loops.
     *
     * This method will fail if there is too much work to be done in a single transaction. If one wants
     * to handle building a range that does not fit in a single transaction, one should use the
     * {@link #buildRange(Key.Evaluated, Key.Evaluated) buildRange()}
     * function that takes an {@link FDBDatabase} as its first parameter.
     *
     * @param store the record store in which to rebuild the range
     * @param start the (inclusive) beginning primary key of the range to build (or <code>null</code> to go to the end)
     * @param end the (exclusive) end primary key of the range to build (or <code>null</code> to go to the end)
     * @return a future that will be ready when the build has completed
     */
    @Nonnull
    public CompletableFuture<Void> buildRange(@Nonnull FDBRecordStore store, @Nullable Key.Evaluated start, @Nullable Key.Evaluated end) {
        RangeSet rangeSet = new RangeSet(store.indexRangeSubspace(index));
        byte[] startBytes = packOrNull(convertOrNull(start));
        byte[] endBytes = packOrNull(convertOrNull(end));
        AsyncIterator<Range> ranges = rangeSet.missingRanges(store.ensureContextActive(), startBytes, endBytes).iterator();
        return ranges.onHasNext().thenCompose(hasNext -> {
            if (hasNext) {
                return AsyncUtil.whileTrue(() -> {
                    Range toBuild = ranges.next();
                    Tuple startTuple = Tuple.fromBytes(toBuild.begin);
                    Tuple endTuple = Arrays.equals(toBuild.end, END_BYTES) ? null : Tuple.fromBytes(toBuild.end);
                    AtomicReference<Tuple> currStart = new AtomicReference<>(startTuple);
                    return AsyncUtil.whileTrue(() ->
                        // Bold claim: this will never cause a RecordBuiltRangeException because of transactions.
                        buildUnbuiltRange(store, currStart.get(), endTuple).thenApply(realEnd -> {
                            if (realEnd != null && !realEnd.equals(endTuple)) {
                                currStart.set(realEnd);
                                return true;
                            } else {
                                return false;
                            }
                        }), store.getExecutor()).thenCompose(vignore -> ranges.onHasNext());
                }, store.getExecutor());
            } else {
                return AsyncUtil.DONE;
            }
        });
    }

    /**
     * Builds (with a retry loop) the index by adding records with primary keys within the given range.
     * This will look for gaps of keys within the given range that haven't yet been rebuilt and then rebuild
     * only those ranges. It will also limit each transaction to the number of records specified by the
     * <code>limit</code> parameter of this class's constructor. In the case that that limit is too high (i.e.,
     * it can't make any progress or errors out on a non-retriable error like <code>transaction_too_large</code>,
     * this method will actually decrease the limit so that less work is attempted each transaction. It will
     * also rate limit itself as to not make too many requests per second.
     *
     * @param start the (inclusive) beginning primary key of the range to build (or <code>null</code> to go from the beginning)
     * @param end the (exclusive) end primary key of the range to build (or <code>null</code> to go to the end)
     * @return a future that will be ready when the build has completed
     */
    @Nonnull
    public CompletableFuture<Void> buildRange(@Nullable Key.Evaluated start, @Nullable Key.Evaluated end) {
        return recordStoreBuilder.getSubspaceProvider().getSubspaceAsync().thenCompose(subspace -> buildRange(subspace, start, end));
    }

    @Nonnull
    private CompletableFuture<Void> buildRange(@Nonnull Subspace subspace, @Nullable Key.Evaluated start, @Nullable Key.Evaluated end) {
        RangeSet rangeSet = new RangeSet(subspace.subspace(Tuple.from(FDBRecordStore.INDEX_RANGE_SPACE_KEY, index.getSubspaceKey())));
        byte[] startBytes = packOrNull(convertOrNull(start));
        byte[] endBytes = packOrNull(convertOrNull(end));
        Queue<Range> rangeDeque = new ArrayDeque<>();
        return rangeSet.missingRanges(runner.getDatabase().database(), startBytes, endBytes)
                .thenAccept(rangeDeque::addAll)
                .thenCompose(vignore -> buildRanges(subspace, rangeSet, rangeDeque));
    }

    @Nonnull
    private CompletableFuture<Void> buildRanges(@Nonnull Subspace subspace, RangeSet rangeSet, Queue<Range> rangeDeque) {
        return AsyncUtil.whileTrue(() -> {
            if (rangeDeque.isEmpty()) {
                return CompletableFuture.completedFuture(false); // We're done.
            }
            Range toBuild = rangeDeque.remove();

            // This only works if the things included within the rangeSet are serialized Tuples.
            Tuple startTuple = Tuple.fromBytes(toBuild.begin);
            Tuple endTuple = Arrays.equals(toBuild.end, END_BYTES) ? null : Tuple.fromBytes(toBuild.end);
            return buildUnbuiltRange(startTuple, endTuple)
                    .handle((realEnd, ex) -> handleBuiltRange(subspace, rangeSet, rangeDeque, startTuple, endTuple, realEnd, ex))
                    .thenCompose(Function.identity());
        }, runner.getExecutor());
    }

    @Nonnull
    private CompletableFuture<Boolean> handleBuiltRange(@Nonnull Subspace subspace, RangeSet rangeSet, Queue<Range> rangeDeque, Tuple startTuple, Tuple endTuple, Tuple realEnd, Throwable ex) {
        final RuntimeException unwrappedEx = ex == null ? null : runner.getDatabase().mapAsyncToSyncException(ex);
        long toWait = (recordsPerSecond == UNLIMITED) ? 0 : 1000 * limit / recordsPerSecond;
        if (unwrappedEx == null) {
            if (realEnd != null && !realEnd.equals(endTuple)) {
                // We didn't make it to the end. Continue on to the next item.
                if (endTuple != null) {
                    rangeDeque.add(new Range(realEnd.pack(), endTuple.pack()));
                } else {
                    rangeDeque.add(new Range(realEnd.pack(), END_BYTES));
                }
            }
            return MoreAsyncUtil.delayedFuture(toWait, TimeUnit.MILLISECONDS).thenApply(vignore3 -> true);
        } else {
            Throwable cause = unwrappedEx;
            while (cause != null) {
                if (cause instanceof RecordBuiltRangeException) {
                    return rangeSet.missingRanges(runner.getDatabase().database(), startTuple.pack(), endTuple.pack())
                            .thenCompose(list -> {
                                rangeDeque.addAll(list);
                                return MoreAsyncUtil.delayedFuture(toWait, TimeUnit.MILLISECONDS);
                            }).thenApply(vignore3 -> true);
                } else {
                    cause = cause.getCause();
                }
            }
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(KeyValueLogMessage.of("possibly non-fatal error encountered building range",
                        LogMessageKeys.RANGE_START, startTuple,
                        LogMessageKeys.RANGE_END, endTuple,
                        LogMessageKeys.SUBSPACE, ByteArrayUtil2.loggable(subspace.pack())), ex);
            }
            throw unwrappedEx; // made it to the bottom, throw original exception
        }
    }

    // Helper function that works on Tuples instead of keys.
    @Nonnull
    private CompletableFuture<Tuple> buildUnbuiltRange(@Nonnull FDBRecordStore store, @Nullable Tuple start, @Nullable Tuple end) {
        CompletableFuture<Tuple> buildFuture = buildRangeOnly(store, start, end, true);

        RangeSet rangeSet = new RangeSet(store.indexRangeSubspace(index));
        byte[] startBytes = packOrNull(start);

        AtomicReference<Tuple> toReturn = new AtomicReference<>();
        return buildFuture.thenCompose(realEnd -> {
            toReturn.set(realEnd);
            return rangeSet.insertRange(store.ensureContextActive(), startBytes, packOrNull(realEnd), true);
        }).thenApply(changed -> {
            if (changed) {
                return toReturn.get();
            } else {
                throw new RecordBuiltRangeException(start, end);
            }
        });
    }

    /**
     * Builds (transactionally) the index by adding records with primary keys within the given range.
     * This requires that the range is initially "unbuilt", i.e., no records within the given
     * range have yet been processed by the index build job. It is acceptable if there
     * are records within that range that have already been added to the index because they were
     * added to the store after the index was added in write-only mode but have not yet been
     * processed by the index build job.
     *
     * Note that this function is not idempotent in that if the first time this function runs, if it
     * fails with <code>commit_unknown_result</code> but the transaction actually succeeds, running this
     * function again will result in a {@link RecordBuiltRangeException} being thrown the second
     * time. Retry loops used by the <code>OnlineIndexer</code> class that call this method
     * handle this contingency. For the most part, this method should only be used by those who know
     * what they are doing. It is included because it is less expensive to make this call if one
     * already knows that the range will be unbuilt, but the caller must be ready to handle the
     * circumstance that the range might be built the second time.
     *
     * Most users should use the
     * {@link #buildRange(FDBRecordStore, Key.Evaluated, Key.Evaluated) buildRange()}
     * method with the same parameters in the case that they want to build a range of keys into the index. That
     * method <i>is</i> idempotent, but it is slightly more costly as it firsts determines what ranges are
     * have not yet been built before building them.
     *
     * @param store the record store in which to rebuild the range
     * @param start the (inclusive) beginning primary key of the range to build (or <code>null</code> to start from the beginning)
     * @param end the (exclusive) end primary key of the range to build (or <code>null</code> to go to the end)
     * @return a future with the key of the first record not processed by this range rebuild
     * @throws RecordBuiltRangeException if the given range contains keys already processed by the index build
     */
    @Nonnull
    public CompletableFuture<Key.Evaluated> buildUnbuiltRange(@Nonnull FDBRecordStore store, @Nullable Key.Evaluated start, @Nullable Key.Evaluated end) {
        return buildUnbuiltRange(store, convertOrNull(start), convertOrNull(end))
                .thenApply(tuple -> (tuple == null) ? null : Key.Evaluated.fromTuple(tuple));
    }

    // Helper function with the same behavior as buildUnbuiltRange, but it works on tuples instead of primary keys.
    @Nonnull
    private CompletableFuture<Tuple> buildUnbuiltRange(@Nullable Tuple start, @Nullable Tuple end) {
        return runAsync(store -> buildUnbuiltRange(store, start, end));
    }

    @VisibleForTesting
    @Nonnull
    CompletableFuture<Key.Evaluated> buildUnbuiltRange(@Nullable Key.Evaluated start, @Nullable Key.Evaluated end) {
        return runAsync(store -> buildUnbuiltRange(store, start, end));
    }

    /**
     * Transactionally rebuild an entire index. This will (1) delete any data in the index that is
     * already there and (2) rebuild the entire key range for the given index. It will attempt to
     * do this within a single transaction, and it may fail if there are too many records, so this
     * is only safe to do for small record stores.
     *
     * Many large use-cases should use the {@link #buildIndexAsync} method along with temporarily
     * changing an index to write-only mode while the index is being rebuilt.
     *
     * @param store the record store in which to rebuild the index
     * @return a future that will be ready when the build has completed
     */
    @Nonnull
    public CompletableFuture<Void> rebuildIndexAsync(@Nonnull FDBRecordStore store) {
        Transaction tr = store.ensureContextActive();
        store.clearIndexData(index);

        // Clear the associated range set and make it instead equal to
        // the complete range. This isn't super necessary, but it is done
        // to avoid (1) concurrent OnlineIndexBuilders doing more work and
        // (2) to allow for write-only indexes to continue to do the right thing.
        RangeSet rangeSet = new RangeSet(store.indexRangeSubspace(index));
        CompletableFuture<Boolean> rangeFuture = rangeSet.clear(tr)
                .thenCompose(vignore -> rangeSet.insertRange(tr, null, null));

        // Rebuild the index by going through all of the records in a transaction.
        AtomicReference<TupleRange> rangeToGo = new AtomicReference<>(recordsRange);
        CompletableFuture<Void> buildFuture = AsyncUtil.whileTrue(() ->
                buildRangeOnly(store, rangeToGo.get(), true).thenApply(nextStart -> {
                    if (nextStart == null) {
                        return false;
                    } else {
                        rangeToGo.set(new TupleRange(nextStart, rangeToGo.get().getHigh(), EndpointType.RANGE_INCLUSIVE, rangeToGo.get().getHighEndpoint()));
                        return true;
                    }
                }), store.getExecutor());

        return CompletableFuture.allOf(rangeFuture, buildFuture);
    }

    /**
     * Transactionally rebuild an entire index.
     * Synchronous version of {@link #rebuildIndexAsync}
     *
     * @param store the record store in which to rebuild the index
     * @see #buildIndex
     */
    public void rebuildIndex(@Nonnull FDBRecordStore store) {
        asyncToSync(rebuildIndexAsync(store));
    }

    /**
     * Builds (transactionally) the endpoints of an index. What this means is that builds everything from the beginning of
     * the key space to the first record and everything from the last record to the end of the key space.
     * There won't be any records within these ranges (except for the last record of the record store), but
     * it does mean that any records in the future that get added to these ranges will correctly update
     * the index. This means, e.g., that if the workload primarily adds records to the record store
     * after the current last record (because perhaps the primary key is based off of an atomic counter
     * or the current time), running this method will be highly contentious, but once it completes,
     * the rest of the index build should happen without any more conflicts.
     *
     * This will return a (possibly null) {@link TupleRange} that contains the primary keys of the
     * first and last records within the record store. This can then be used to either build the
     * range right away or to then divy-up the remaining ranges between multiple agents working
     * in parallel if one desires.
     *
     * @param store the record store in which to rebuild the index
     * @return a future that will contain the range of records in the interior of the record store
     */
    @Nonnull
    public CompletableFuture<TupleRange> buildEndpoints(@Nonnull FDBRecordStore store) {
        final RangeSet rangeSet = new RangeSet(store.indexRangeSubspace(index));
        if (TupleRange.ALL.equals(recordsRange)) {
            return buildEndpoints(store, rangeSet);
        }
        // If records do not occupy whole range, first mark outside as built.
        final Range asRange = recordsRange.toRange();
        return CompletableFuture.allOf(
                rangeSet.insertRange(store.ensureContextActive(), null, asRange.begin),
                rangeSet.insertRange(store.ensureContextActive(), asRange.end, null))
                .thenCompose(vignore -> buildEndpoints(store, rangeSet));
    }

    @Nonnull
    private CompletableFuture<TupleRange> buildEndpoints(@Nonnull FDBRecordStore store, @Nonnull RangeSet rangeSet) {
        final ExecuteProperties limit1 = ExecuteProperties.newBuilder()
                .setReturnedRowLimit(1)
                .setIsolationLevel(IsolationLevel.SERIALIZABLE)
                .build();
        final ScanProperties forward = new ScanProperties(limit1);
        RecordCursor<FDBStoredRecord<Message>> beginCursor = store.scanRecords(recordsRange, null, forward);
        CompletableFuture<Tuple> begin = beginCursor.onHasNext().thenCompose(present -> {
            if (present) {
                Tuple firstTuple = beginCursor.next().getPrimaryKey();
                return buildRange(store, null, firstTuple).thenApply(vignore -> firstTuple);
            } else {
                // Empty range -- add the whole thing.
                return rangeSet.insertRange(store.ensureContextActive(), null, null).thenApply(bignore -> null);
            }
        });

        final ScanProperties backward = new ScanProperties(limit1, true);
        RecordCursor<FDBStoredRecord<Message>> endCursor = store.scanRecords(recordsRange, null, backward);
        CompletableFuture<Tuple> end = endCursor.onHasNext().thenCompose(present -> {
            if (present) {
                Tuple lastTuple = endCursor.next().getPrimaryKey();
                return buildRange(store, lastTuple, null).thenApply(vignore -> lastTuple);
            } else {
                // As the range is empty, the whole range needs to be added, but that is accomplished
                // by the above future, so this has nothing to do.
                return CompletableFuture.completedFuture(null);
            }
        });

        return begin.thenCombine(end, (firstTuple, lastTuple) -> {
            if (firstTuple == null || firstTuple.equals(lastTuple)) {
                return null;
            } else {
                return new TupleRange(firstTuple, lastTuple, EndpointType.RANGE_INCLUSIVE, EndpointType.RANGE_EXCLUSIVE);
            }
        });
    }

    /**
     * Builds (with a retry loop) the endpoints of an index. See the
     * {@link #buildEndpoints(FDBRecordStore) buildEndpoints()} method that takes
     * an {@link FDBRecordStore} as its parameter for more details. This will retry on that function
     * until it gets a non-exceptional result and return the results back.
     *
     * @return a future that will contain the range of records in the interior of the record store
     */
    @Nonnull
    public CompletableFuture<TupleRange> buildEndpoints() {
        return runAsync(this::buildEndpoints);
    }

    /**
     * Builds an index across multiple transactions. This will honor the rate-limiting
     * parameters set in the constructor of this class. It will also retry
     * any retriable errors that it encounters while it runs the build. At the
     * end, it will mark the index readable in the store if specified.
     *
     * @param markReadable whether to mark the index as readable after building the index
     * @return a future that will be ready when the build has completed
     */
    @Nonnull
    public CompletableFuture<Void> buildIndexAsync(boolean markReadable) {
        CompletableFuture<Void> buildFuture = buildEndpoints().thenCompose(tupleRange -> {
            if (tupleRange != null) {
                return buildRange(Key.Evaluated.fromTuple(tupleRange.getLow()), Key.Evaluated.fromTuple(tupleRange.getHigh()));
            } else {
                return CompletableFuture.completedFuture(null);
            }
        });

        if (markReadable) {
            return buildFuture.thenCompose(vignore ->
                runner.runAsync(context ->
                        openRecordStore(context)
                                .thenCompose(store -> store.markIndexReadable(index))
                                .thenApply(ignore -> null))
            );
        } else {
            return buildFuture;
        }
    }

    /**
     * Builds an index across multiple transactions. This will honor the rate-limiting
     * parameters set in the constructor of this class. It will also retry
     * any retriable errors that it encounters while it runs the build.
     *
     * @return a future that will be ready when the build has completed
     */
    @Nonnull
    public CompletableFuture<Void> buildIndexAsync() {
        return buildIndexAsync(true);
    }

    /**
     * Builds an index across multiple transactions.
     * Synchronous version of {@link #buildIndexAsync}.
     * @param markReadable whether to mark the index as readable after building the index
     */
    @Nonnull
    public void buildIndex(boolean markReadable) {
        asyncToSync(buildIndexAsync(markReadable));
    }

    /**
     * Builds an index across multiple transactions.
     * Synchronous version of {@link #buildIndexAsync}.
     */
    @Nonnull
    public void buildIndex() {
        asyncToSync(buildIndexAsync());
    }

    /**
     * Wait for asynchronous index build to complete.
     * @param buildIndexFuture the result of {@link #buildIndexAsync}
     */
    public void asyncToSync(@Nonnull CompletableFuture<Void> buildIndexFuture) {
        runner.asyncToSync(FDBStoreTimer.Waits.WAIT_ONLINE_BUILD_INDEX, buildIndexFuture);
    }

    /**
     * Builder for {@link OnlineIndexer}.
     *
     * <pre><code>
     * OnlineIndexer.newBuilder().setRecordStoreBuilder(recordStoreBuilder).setIndex(index).build()
     * </code></pre>
     *
     * <pre><code>
     * OnlineIndexer.newBuilder().setDatabase(fdb).setMetaData(metaData).setSubspace(subspace).setIndex(index).build()
     * </code></pre>
     *
     */
    @API(API.Status.UNSTABLE)
    public static class Builder {
        @Nullable
        protected FDBDatabaseRunner runner;
        @Nullable
        protected FDBRecordStore.Builder recordStoreBuilder;
        @Nullable
        protected Index index;
        @Nullable
        protected Collection<RecordType> recordTypes;

        protected int limit = DEFAULT_LIMIT;
        protected int maxRetries = DEFAULT_MAX_RETRIES;
        protected int recordsPerSecond = DEFAULT_RECORDS_PER_SECOND;

        protected Builder() {
        }

        /**
         * Get the runner that will be used to call into the database.
         * @return the runner that connects to the target database
         */
        @Nullable
        public FDBDatabaseRunner getRunner() {
            return runner;
        }

        /**
         * Set the runner that will be used to call into the database.
         *
         * Normally the runner is gotten from {@link #setDatabase} or {@link #setRecordStore} or {@link #setRecordStoreBuilder}.
         * @param runner the runner that connects to the target database
         * @return this builder
         */
        public Builder setRunner(@Nullable FDBDatabaseRunner runner) {
            this.runner = runner;
            return this;
        }

        /**
         * Set the database in which to run the indexing.
         *
         * Normally the database is gotten from {@link #setRecordStore} or {@link #setRecordStoreBuilder}.
         * @param database the target database
         * @return this builder
         */
        public Builder setDatabase(@Nonnull FDBDatabase database) {
            this.runner = database.newRunner();
            return this;
        }

        /**
         * Get the record store builder that will be used to open record store instances for indexing.
         * @return the record store builder
         */
        @Nullable
        @SuppressWarnings("squid:S1452")
        public FDBRecordStore.Builder getRecordStoreBuilder() {
            return recordStoreBuilder;
        }

        /**
         * Set the record store builder that will be used to open record store instances for indexing.
         * @param recordStoreBuilder the record store builder
         * @return this builder
         * @see #setRecordStore
         */
        public Builder setRecordStoreBuilder(@Nonnull FDBRecordStore.Builder recordStoreBuilder) {
            this.recordStoreBuilder = recordStoreBuilder.copyBuilder().setContext(null);
            if (runner == null && recordStoreBuilder.getContext() != null) {
                runner = recordStoreBuilder.getContext().getDatabase().newRunner();
                runner.setTimer(recordStoreBuilder.getContext().getTimer());
                runner.setMdcContext(recordStoreBuilder.getContext().getMdcContext());
            }
            return this;
        }

        /**
         * Set the record store that will be used as a template to open record store instances for indexing.
         * @param recordStore the target record store
         * @return this builder
         */
        public Builder setRecordStore(@Nonnull FDBRecordStore recordStore) {
            recordStoreBuilder = recordStore.asBuilder().setContext(null);
            if (runner == null) {
                runner = recordStore.getRecordContext().getDatabase().newRunner();
                runner.setTimer(recordStore.getTimer());
                runner.setMdcContext(recordStore.getRecordContext().getMdcContext());
            }
            return this;
        }

        /**
         * Get the index to be built.
         * @return the index to be built
         */
        @Nullable
        public Index getIndex() {
            return index;
        }

        /**
         * Set the index to be built.
         * @param index the index to be built
         * @return this builder
         */
        public Builder setIndex(@Nullable Index index) {
            this.index = index;
            return this;
        }

        /**
         * Set the index to be built.
         * @param indexName the index to be built
         * @return this builder
         */
        public Builder setIndex(@Nonnull String indexName) {
            this.index = getRecordMetaData().getIndex(indexName);
            return this;
        }

        /**
         * Get the explicit set of record types to be indexed.
         *
         * Normally, all record types associated with the chosen index will be indexed.
         * @return the record types to be indexed
         */
        @Nullable
        public Collection<RecordType> getRecordTypes() {
            return recordTypes;
        }

        /**
         * Set the explicit set of record types to be indexed.
         *
         * Normally, record types are inferred from {@link #setIndex}.
         * @param recordTypes the record types to be indexed or {@code null} to infer from the index
         * @return this builder
         */
        public Builder setRecordTypes(@Nullable Collection<RecordType> recordTypes) {
            this.recordTypes = recordTypes;
            return this;
        }

        /**
         * Get the maximum number of records to process in one transaction.
         * @return the maximum number of records to process in one transaction
         */
        public int getLimit() {
            return limit;
        }

        /**
         * Set the maximum number of records to process in one transaction.
         *
         * The default limit is {@link #DEFAULT_LIMIT} = {@value #DEFAULT_LIMIT}.
         * @param limit the maximum number of records to process in one transaction
         * @return this builder
         */
        public Builder setLimit(int limit) {
            this.limit = limit;
            return this;
        }

        /**
         * Get the maximum number of times to retry a single range rebuild.
         * @return the maximum number of times to retry a single range rebuild
         */
        public int getMaxRetries() {
            return maxRetries;
        }

        /**
         * Set the maximum number of times to retry a single range rebuild.
         *
         * The default number of retries is {@link #DEFAULT_MAX_RETRIES} = {@value #DEFAULT_MAX_RETRIES}.
         * @param maxRetries the maximum number of times to retry a single range rebuild
         * @return this builder
         */
        public Builder setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Get the maximum number of records to process in a single second.
         * @return the maximum number of records to process in a single second
         */
        public int getRecordsPerSecond() {
            return recordsPerSecond;
        }

        /**
         * Set the maximum number of records to process in a single second.
         *
         * The default number of retries is {@link #DEFAULT_RECORDS_PER_SECOND} = {@value #DEFAULT_RECORDS_PER_SECOND}.
         * @param recordsPerSecond the maximum number of records to process in a single second.
         * @return this builder
         */
        public Builder setRecordsPerSecond(int recordsPerSecond) {
            this.recordsPerSecond = recordsPerSecond;
            return this;
        }

        /**
         * Get the timer used in {@link #buildIndex}.
         * @return the timer or <code>null</code> if none is set
         */
        @Nullable
        public FDBStoreTimer getTimer() {
            if (runner == null) {
                throw new MetaDataException("timer is only known after runner has been set");
            }
            return runner.getTimer();
        }

        /**
         * Set the timer used in {@link #buildIndex}.
         * @param timer timer to use
         * @return this builder
         */
        public Builder setTimer(@Nullable FDBStoreTimer timer) {
            if (runner == null) {
                throw new MetaDataException("timer can only be set after runner has been set");
            }
            runner.setTimer(timer);
            return this;
        }

        /**
         * Get the logging context used in {@link #buildIndex}.
         * @return the logging context of <code>null</code> if none is set
         */
        @Nullable
        public Map<String, String> getMdcContext() {
            if (runner == null) {
                throw new MetaDataException("logging context is only known after runner has been set");
            }
            return runner.getMdcContext();
        }

        /**
         * Set the logging context used in {@link #buildIndex}.
         * @param mdcContext the logging context to set while running
         * @return this builder
         * @see FDBDatabase#openContext(Map,FDBStoreTimer)
         */
        public Builder setMdcContext(@Nullable Map<String, String> mdcContext) {
            if (runner == null) {
                throw new MetaDataException("logging context can only be set after runner has been set");
            }
            runner.setMdcContext(mdcContext);
            return this;
        }

        /**
         * Get the maximum number of transaction retry attempts.
         * @return the maximum number of attempts
         * @see FDBDatabaseRunner#getMaxAttempts
         */
        public int getMaxAttempts() {
            if (runner == null) {
                throw new MetaDataException("maximum attempts is only known after runner has been set");
            }
            return runner.getMaxAttempts();
        }

        /**
         * Set the maximum number of transaction retry attempts.
         * @param maxAttempts the maximum number of attempts
         * @return this builder
         * @see FDBDatabaseRunner#setMaxAttempts
         */
        public Builder setMaxAttempts(int maxAttempts) {
            if (runner == null) {
                throw new MetaDataException("maximum attempts can only be set after runner has been set");
            }
            runner.setMaxAttempts(maxAttempts);
            return this;
        }

        /**
         * Get the maximum delay between transaction retry attempts.
         * @return the maximum delay
         * @see FDBDatabaseRunner#getMaxDelayMillis
         */
        public long getMaxDelayMillis() {
            if (runner == null) {
                throw new MetaDataException("maximum delay is only known after runner has been set");
            }
            return runner.getMaxDelayMillis();
        }

        /**
         * Set the maximum delay between transaction retry attempts.
         * @param maxDelayMillis the maximum delay
         * @return this builder
         * @see FDBDatabaseRunner#setMaxDelayMillis
         */
        public Builder setMaxDelayMillis(long maxDelayMillis) {
            if (runner == null) {
                throw new MetaDataException("maximum delay can only be set after runner has been set");
            }
            runner.setMaxDelayMillis(maxDelayMillis);
            return this;
        }

        /**
         * Get the initial delay between transaction retry attempts.
         * @return the initial delay
         * @see FDBDatabaseRunner#getInitialDelayMillis
         */
        public long getInitialDelayMillis() {
            if (runner == null) {
                throw new MetaDataException("initial delay is only known after runner has been set");
            }
            return runner.getInitialDelayMillis();
        }

        /**
         * Set the initial delay between transaction retry attempts.
         * @param initialDelayMillis the initial delay
         * @return this builder
         * @see FDBDatabaseRunner#setInitialDelayMillis
         */
        public Builder setInitialDelayMillis(long initialDelayMillis) {
            if (runner == null) {
                throw new MetaDataException("initial delay can only be set after runner has been set");
            }
            runner.setInitialDelayMillis(initialDelayMillis);
            return this;
        }

        /**
         * Set the {@link IndexMaintenanceFilter} to use while building the index.
         *
         * Normally this is set by {@link #setRecordStore} or {@link #setRecordStoreBuilder}.
         * @param indexMaintenanceFilter the index filter to use
         * @return this builder
         */
        public Builder setIndexMaintenanceFilter(@Nonnull IndexMaintenanceFilter indexMaintenanceFilter) {
            if (recordStoreBuilder == null) {
                throw new MetaDataException("index filter can only be set after record store builder has been set");
            }
            recordStoreBuilder.setIndexMaintenanceFilter(indexMaintenanceFilter);
            return this;
        }

        /**
         * Set the {@link RecordSerializer} to use while building the index.
         *
         * Normally this is set by {@link #setRecordStore} or {@link #setRecordStoreBuilder}.
         * @param serializer the serializer to use
         * @return this builder
         */
        public Builder setSerializer(@Nonnull RecordSerializer<Message> serializer) {
            if (recordStoreBuilder == null) {
                throw new MetaDataException("serializer can only be set after record store builder has been set");
            }
            recordStoreBuilder.setSerializer(serializer);
            return this;
        }

        /**
         * Set the store format version to use while building the index.
         *
         * Normally this is set by {@link #setRecordStore} or {@link #setRecordStoreBuilder}.
         * @param formatVersion the format version to use
         * @return this builder
         */
        public Builder setFormatVersion(int formatVersion) {
            if (recordStoreBuilder == null) {
                throw new MetaDataException("format version can only be set after record store builder has been set");
            }
            recordStoreBuilder.setFormatVersion(formatVersion);
            return this;
        }

        @Nonnull
        private RecordMetaData getRecordMetaData() {
            if (recordStoreBuilder == null) {
                throw new MetaDataException("record store must be set");
            }
            if (recordStoreBuilder.getMetaDataProvider() == null) {
                throw new MetaDataException("record store builder must include metadata");
            }
            return recordStoreBuilder.getMetaDataProvider().getRecordMetaData();
        }

        /**
         * Set the meta-data to use when indexing.
         * @param metaDataProvider meta-data to use
         * @return this builder
         */
        public Builder setMetaData(@Nonnull RecordMetaDataProvider metaDataProvider) {
            if (recordStoreBuilder == null) {
                recordStoreBuilder = FDBRecordStore.newBuilder();
            }
            recordStoreBuilder.setMetaDataProvider(metaDataProvider);
            return this;
        }

        /**
         * Set the subspace of the record store in which to build the index.
         * @param subspaceProvider subspace to use
         * @return this builder
         */
        public Builder setSubspaceProvider(@Nonnull SubspaceProvider subspaceProvider) {
            if (recordStoreBuilder == null) {
                recordStoreBuilder = FDBRecordStore.newBuilder();
            }
            recordStoreBuilder.setSubspaceProvider(subspaceProvider);
            return this;
        }

        /**
         * Set the subspace of the record store in which to build the index.
         * @param subspace subspace to use
         * @return this builder
         */
        public Builder setSubspace(@Nonnull Subspace subspace) {
            if (recordStoreBuilder == null) {
                recordStoreBuilder = FDBRecordStore.newBuilder();
            }
            recordStoreBuilder.setSubspace(subspace);
            return this;
        }

        /**
         * Build an {@link OnlineIndexer}.
         * @return a new online indexer
         */
        public OnlineIndexer build() {
            validate();
            return new OnlineIndexer(runner, recordStoreBuilder, index, recordTypes, limit, maxRetries, recordsPerSecond);
        }

        protected void validate() {
            validateIndex();
            validateLimits();
        }

        // Check pointer equality to make sure other objects really came from given metaData.
        // Also resolve record types to use if not specified.
        private void validateIndex() {
            final RecordMetaData metaData = getRecordMetaData();
            if (index == null) {
                throw new MetaDataException("index must be set");
            }
            if (!metaData.hasIndex(index.getName()) || index != metaData.getIndex(index.getName())) {
                throw new MetaDataException("Index " + index.getName() + " not contained within specified metadata");
            }
            if (recordTypes == null) {
                recordTypes = metaData.recordTypesForIndex(index);
            } else {
                for (RecordType recordType : recordTypes) {
                    if (recordType != metaData.getRecordTypes().get(recordType.getName())) {
                        throw new MetaDataException("Record type " + recordType.getName() + " not contained within specified metadata");
                    }
                }
            }
        }

        private void validateLimits() {
            checkPositive(maxRetries, "maximum retries");
            checkPositive(limit, "record limit");
            checkPositive(recordsPerSecond, "records per second value");
        }

        private static void checkPositive(int value, String desc) {
            if (value <= 0) {
                throw new RecordCoreException("Non-positive value " + value + " given for " + desc);
            }
        }

    }

    /**
     * Create an online indexer builder.
     * @return a new online indexer builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Create an online indexer for the given record store and index.
     * @param recordStore record store in which to index
     * @param index name of index to build
     * @return a new online indexer
     */
    public static OnlineIndexer forRecordStoreAndIndex(@Nonnull FDBRecordStore recordStore, @Nonnull String index) {
        return newBuilder().setRecordStore(recordStore).setIndex(index).build();
    }

}
