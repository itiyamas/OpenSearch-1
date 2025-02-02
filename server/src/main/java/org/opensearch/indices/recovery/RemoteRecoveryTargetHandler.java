/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.indices.recovery;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.store.RateLimiter;
import org.opensearch.LegacyESVersion;
import org.opensearch.OpenSearchException;
import org.opensearch.ExceptionsHelper;
import org.opensearch.action.ActionListener;
import org.opensearch.action.ActionListenerResponseHandler;
import org.opensearch.action.support.RetryableAction;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.breaker.CircuitBreakingException;
import org.opensearch.common.bytes.BytesReference;
import org.opensearch.common.io.stream.Writeable;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.CancellableThreads;
import org.opensearch.common.util.concurrent.ConcurrentCollections;
import org.opensearch.common.util.concurrent.OpenSearchRejectedExecutionException;
import org.opensearch.index.seqno.ReplicationTracker;
import org.opensearch.index.seqno.RetentionLeases;
import org.opensearch.index.shard.ShardId;
import org.opensearch.index.store.Store;
import org.opensearch.index.store.StoreFileMetadata;
import org.opensearch.index.translog.Translog;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.ConnectTransportException;
import org.opensearch.transport.EmptyTransportResponseHandler;
import org.opensearch.transport.RemoteTransportException;
import org.opensearch.transport.SendRequestTransportException;
import org.opensearch.transport.TransportRequestOptions;
import org.opensearch.transport.TransportResponse;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class RemoteRecoveryTargetHandler implements RecoveryTargetHandler {

    private static final Logger logger = LogManager.getLogger(RemoteRecoveryTargetHandler.class);

    private final TransportService transportService;
    private final ThreadPool threadPool;
    private final long recoveryId;
    private final ShardId shardId;
    private final DiscoveryNode targetNode;
    private final RecoverySettings recoverySettings;
    private final Map<Object, RetryableAction<?>> onGoingRetryableActions = ConcurrentCollections.newConcurrentMap();

    private final TransportRequestOptions translogOpsRequestOptions;
    private final TransportRequestOptions fileChunkRequestOptions;

    private final AtomicLong bytesSinceLastPause = new AtomicLong();
    private final AtomicLong requestSeqNoGenerator = new AtomicLong(0);

    private final Consumer<Long> onSourceThrottle;
    private final boolean retriesSupported;
    private volatile boolean isCancelled = false;

    public RemoteRecoveryTargetHandler(long recoveryId, ShardId shardId, TransportService transportService,
                                       DiscoveryNode targetNode, RecoverySettings recoverySettings, Consumer<Long> onSourceThrottle) {
        this.transportService = transportService;
        this.threadPool = transportService.getThreadPool();
        this.recoveryId = recoveryId;
        this.shardId = shardId;
        this.targetNode = targetNode;
        this.recoverySettings = recoverySettings;
        this.onSourceThrottle = onSourceThrottle;
        this.translogOpsRequestOptions = TransportRequestOptions.builder()
                .withType(TransportRequestOptions.Type.RECOVERY)
                .withTimeout(recoverySettings.internalActionLongTimeout())
                .build();
        this.fileChunkRequestOptions = TransportRequestOptions.builder()
                .withType(TransportRequestOptions.Type.RECOVERY)
                .withTimeout(recoverySettings.internalActionTimeout())
                .build();
        this.retriesSupported = targetNode.getVersion().onOrAfter(LegacyESVersion.V_7_9_0);
    }

    public DiscoveryNode targetNode() {
        return targetNode;
    }

    @Override
    public void prepareForTranslogOperations(int totalTranslogOps, ActionListener<Void> listener) {
        final String action = PeerRecoveryTargetService.Actions.PREPARE_TRANSLOG;
        final long requestSeqNo = requestSeqNoGenerator.getAndIncrement();
        final RecoveryPrepareForTranslogOperationsRequest request =
            new RecoveryPrepareForTranslogOperationsRequest(recoveryId, requestSeqNo, shardId, totalTranslogOps);
        final TransportRequestOptions options =
            TransportRequestOptions.builder().withTimeout(recoverySettings.internalActionTimeout()).build();
        final Writeable.Reader<TransportResponse.Empty> reader = in -> TransportResponse.Empty.INSTANCE;
        final ActionListener<TransportResponse.Empty> responseListener = ActionListener.map(listener, r -> null);
        executeRetryableAction(action, request, options, responseListener, reader);
    }

    @Override
    public void finalizeRecovery(final long globalCheckpoint, final long trimAboveSeqNo, final ActionListener<Void> listener) {
        final String action = PeerRecoveryTargetService.Actions.FINALIZE;
        final long requestSeqNo = requestSeqNoGenerator.getAndIncrement();
        final RecoveryFinalizeRecoveryRequest request =
            new RecoveryFinalizeRecoveryRequest(recoveryId, requestSeqNo, shardId, globalCheckpoint, trimAboveSeqNo);
        final TransportRequestOptions options =
            TransportRequestOptions.builder().withTimeout(recoverySettings.internalActionLongTimeout()).build();
        final Writeable.Reader<TransportResponse.Empty> reader = in -> TransportResponse.Empty.INSTANCE;
        final ActionListener<TransportResponse.Empty> responseListener = ActionListener.map(listener, r -> null);
        executeRetryableAction(action, request, options, responseListener, reader);
    }

    @Override
    public void handoffPrimaryContext(final ReplicationTracker.PrimaryContext primaryContext) {
        transportService.submitRequest(
                targetNode,
                PeerRecoveryTargetService.Actions.HANDOFF_PRIMARY_CONTEXT,
                new RecoveryHandoffPrimaryContextRequest(recoveryId, shardId, primaryContext),
                TransportRequestOptions.builder().withTimeout(recoverySettings.internalActionTimeout()).build(),
                EmptyTransportResponseHandler.INSTANCE_SAME).txGet();
    }

    @Override
    public void indexTranslogOperations(
            final List<Translog.Operation> operations,
            final int totalTranslogOps,
            final long maxSeenAutoIdTimestampOnPrimary,
            final long maxSeqNoOfDeletesOrUpdatesOnPrimary,
            final RetentionLeases retentionLeases,
            final long mappingVersionOnPrimary,
            final ActionListener<Long> listener) {
        final String action = PeerRecoveryTargetService.Actions.TRANSLOG_OPS;
        final long requestSeqNo = requestSeqNoGenerator.getAndIncrement();
        final RecoveryTranslogOperationsRequest request = new RecoveryTranslogOperationsRequest(
                recoveryId,
                requestSeqNo,
                shardId,
                operations,
                totalTranslogOps,
                maxSeenAutoIdTimestampOnPrimary,
                maxSeqNoOfDeletesOrUpdatesOnPrimary,
                retentionLeases,
                mappingVersionOnPrimary);
        final Writeable.Reader<RecoveryTranslogOperationsResponse> reader = RecoveryTranslogOperationsResponse::new;
        final ActionListener<RecoveryTranslogOperationsResponse> responseListener = ActionListener.map(listener, r -> r.localCheckpoint);
        executeRetryableAction(action, request, translogOpsRequestOptions, responseListener, reader);
    }

    @Override
    public void receiveFileInfo(List<String> phase1FileNames, List<Long> phase1FileSizes, List<String> phase1ExistingFileNames,
                                List<Long> phase1ExistingFileSizes, int totalTranslogOps, ActionListener<Void> listener) {
        final String action = PeerRecoveryTargetService.Actions.FILES_INFO;
        final long requestSeqNo = requestSeqNoGenerator.getAndIncrement();
        RecoveryFilesInfoRequest request = new RecoveryFilesInfoRequest(recoveryId, requestSeqNo, shardId, phase1FileNames, phase1FileSizes,
            phase1ExistingFileNames, phase1ExistingFileSizes, totalTranslogOps);
        final TransportRequestOptions options =
            TransportRequestOptions.builder().withTimeout(recoverySettings.internalActionTimeout()).build();
        final Writeable.Reader<TransportResponse.Empty> reader = in -> TransportResponse.Empty.INSTANCE;
        final ActionListener<TransportResponse.Empty> responseListener = ActionListener.map(listener, r -> null);
        executeRetryableAction(action, request, options, responseListener, reader);
    }

    @Override
    public void cleanFiles(int totalTranslogOps, long globalCheckpoint, Store.MetadataSnapshot sourceMetadata,
                           ActionListener<Void> listener) {
        final String action = PeerRecoveryTargetService.Actions.CLEAN_FILES;
        final long requestSeqNo = requestSeqNoGenerator.getAndIncrement();
        final RecoveryCleanFilesRequest request =
            new RecoveryCleanFilesRequest(recoveryId, requestSeqNo, shardId, sourceMetadata, totalTranslogOps, globalCheckpoint);
        final TransportRequestOptions options =
            TransportRequestOptions.builder().withTimeout(recoverySettings.internalActionTimeout()).build();
        final Writeable.Reader<TransportResponse.Empty> reader = in -> TransportResponse.Empty.INSTANCE;
        final ActionListener<TransportResponse.Empty> responseListener = ActionListener.map(listener, r -> null);
        executeRetryableAction(action, request, options, responseListener, reader);
    }

    @Override
    public void writeFileChunk(StoreFileMetadata fileMetadata, long position, BytesReference content,
                               boolean lastChunk, int totalTranslogOps, ActionListener<Void> listener) {
        // Pause using the rate limiter, if desired, to throttle the recovery
        final long throttleTimeInNanos;
        // always fetch the ratelimiter - it might be updated in real-time on the recovery settings
        final RateLimiter rl = recoverySettings.rateLimiter();
        if (rl != null) {
            long bytes = bytesSinceLastPause.addAndGet(content.length());
            if (bytes > rl.getMinPauseCheckBytes()) {
                // Time to pause
                bytesSinceLastPause.addAndGet(-bytes);
                try {
                    throttleTimeInNanos = rl.pause(bytes);
                    onSourceThrottle.accept(throttleTimeInNanos);
                } catch (IOException e) {
                    throw new OpenSearchException("failed to pause recovery", e);
                }
            } else {
                throttleTimeInNanos = 0;
            }
        } else {
            throttleTimeInNanos = 0;
        }

        final String action = PeerRecoveryTargetService.Actions.FILE_CHUNK;
        final long requestSeqNo = requestSeqNoGenerator.getAndIncrement();
        /* we send estimateTotalOperations with every request since we collect stats on the target and that way we can
         * see how many translog ops we accumulate while copying files across the network. A future optimization
         * would be in to restart file copy again (new deltas) if we have too many translog ops are piling up.
         */
        final RecoveryFileChunkRequest request = new RecoveryFileChunkRequest(
            recoveryId, requestSeqNo, shardId, fileMetadata, position, content, lastChunk, totalTranslogOps, throttleTimeInNanos);
        final Writeable.Reader<TransportResponse.Empty> reader = in -> TransportResponse.Empty.INSTANCE;
        executeRetryableAction(action, request, fileChunkRequestOptions, ActionListener.map(listener, r -> null), reader);
    }

    @Override
    public void cancel() {
        isCancelled = true;
        if (onGoingRetryableActions.isEmpty()) {
            return;
        }
        final RuntimeException exception = new CancellableThreads.ExecutionCancelledException("recovery was cancelled");
        // Dispatch to generic as cancellation calls can come on the cluster state applier thread
        threadPool.generic().execute(() -> {
            for (RetryableAction<?> action : onGoingRetryableActions.values()) {
                action.cancel(exception);
            }
            onGoingRetryableActions.clear();
        });
    }

    private <T extends TransportResponse> void executeRetryableAction(String action, RecoveryTransportRequest request,
                                                                      TransportRequestOptions options, ActionListener<T> actionListener,
                                                                      Writeable.Reader<T> reader) {
        final Object key = new Object();
        final ActionListener<T> removeListener = ActionListener.runBefore(actionListener, () -> onGoingRetryableActions.remove(key));
        final TimeValue initialDelay = TimeValue.timeValueMillis(200);
        final TimeValue timeout = recoverySettings.internalActionRetryTimeout();
        final RetryableAction<T> retryableAction = new RetryableAction<T>(logger, threadPool, initialDelay, timeout, removeListener) {

            @Override
            public void tryAction(ActionListener<T> listener) {
                transportService.sendRequest(targetNode, action, request, options,
                    new ActionListenerResponseHandler<>(listener, reader, ThreadPool.Names.GENERIC));
            }

            @Override
            public boolean shouldRetry(Exception e) {
                return retriesSupported && retryableException(e);
            }
        };
        onGoingRetryableActions.put(key, retryableAction);
        retryableAction.run();
        if (isCancelled) {
            retryableAction.cancel(new CancellableThreads.ExecutionCancelledException("recovery was cancelled"));
        }
    }

    private static boolean retryableException(Exception e) {
        if (e instanceof ConnectTransportException) {
            return true;
        } else if (e instanceof SendRequestTransportException) {
            final Throwable cause = ExceptionsHelper.unwrapCause(e);
            return cause instanceof ConnectTransportException;
        } else if (e instanceof RemoteTransportException) {
            final Throwable cause = ExceptionsHelper.unwrapCause(e);
            return cause instanceof CircuitBreakingException ||
                cause instanceof OpenSearchRejectedExecutionException;
        }
        return false;
    }
}
