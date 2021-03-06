/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl;

import com.hazelcast.core.ExecutionCallback;
import com.hazelcast.core.ICompletableFuture;
import com.hazelcast.core.LocalMemberResetException;
import com.hazelcast.core.MemberLeftException;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.impl.util.NonCompletableFuture;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.LoggingService;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.spi.exception.TargetDisconnectedException;
import com.hazelcast.spi.exception.TargetNotMemberException;
import com.hazelcast.spi.serialization.SerializationService;

import javax.annotation.Nonnull;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.hazelcast.jet.impl.util.ExceptionUtil.peel;
import static com.hazelcast.jet.impl.util.ExceptionUtil.rethrow;
import static com.hazelcast.jet.impl.util.Util.memoizeConcurrent;
import static com.hazelcast.jet.impl.util.Util.toLocalDateTime;

/**
 * Base {@link Job} implementation for both client and member proxy.
 */
public abstract class AbstractJobProxy<T> implements Job {

    private final long jobId;
    private final ILogger logger;
    private final T container;

    /**
     * Future that will be completed when we learn that the coordinator
     * completed the job, but only if {@link #joinedJob} is true.
     */
    private final NonCompletableFuture future = new NonCompletableFuture();

    // Flag which indicates if this proxy has sent a request to join the job result or not
    private final AtomicBoolean joinedJob = new AtomicBoolean();
    private final ExecutionCallback<Void> joinJobCallback = new JoinJobCallback();

    private volatile JobConfig jobConfig;
    private final Supplier<Long> submissionTimeSup = memoizeConcurrent(this::doGetJobSubmissionTime);

    AbstractJobProxy(T container, long jobId) {
        this.jobId = jobId;
        this.container = container;
        this.logger = loggingService().getLogger(Job.class);
    }

    AbstractJobProxy(T container, long jobId, DAG dag, JobConfig config) {
        this.jobId = jobId;
        this.container = container;
        this.logger = loggingService().getLogger(Job.class);

        try {
            doSubmitJob(dag, config);
            joinedJob.set(true);
            doInvokeJoinJob();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public long getId() {
        return jobId;
    }

    @Nonnull @Override
    public JobConfig getConfig() {
        // The common path will use a single volatile load
        JobConfig loadResult = jobConfig;
        if (loadResult != null) {
            return loadResult;
        }
        synchronized (this) {
            // The uncommon path can use simpler code with multiple volatile loads
            if (jobConfig != null) {
                return jobConfig;
            }
            jobConfig = doGetJobConfig();
            if (jobConfig == null) {
                throw new NullPointerException("Supplier returned null");
            }
            return jobConfig;
        }
    }

    /**
     * Returns the string {@code <jobId> (name <jobName>)} without risking
     * triggering of lazy-loading of JobConfig: if we don't have it, it will
     * say {@code name ??}. If we have it and it is null, it will say {@code
     * name ''}.
     */
    private String idAndName() {
        JobConfig config = jobConfig;
        return getIdString() + " (name "
                + (config != null ? "'" + (config.getName() != null ? config.getName() : "") + "'" : "??")
                + ')';
    }

    @Nonnull @Override
    public CompletableFuture<Void> getFuture() {
        if (joinedJob.compareAndSet(false, true)) {
            doInvokeJoinJob();
        }
        return future;
    }

    @Override
    public long getSubmissionTime() {
        return submissionTimeSup.get();
    }

    @Override
    public void cancel() {
        terminate(TerminationMode.CANCEL_FORCEFUL);
    }

    @Override
    public void restart() {
        terminate(TerminationMode.RESTART_GRACEFUL);
    }

    @Override
    public void suspend() {
        terminate(TerminationMode.SUSPEND_GRACEFUL);
    }

    private void terminate(TerminationMode mode) {
        logger.fine("Sending " + mode + " request for job " + idAndName());
        while (true) {
            try {
                invokeTerminateJob(mode).get();
                break;
            } catch (Exception e) {
                if (!isRestartable(e)) {
                    throw rethrow(e);
                }
                logger.fine("Re-sending " + mode + " request for job " + idAndName());
            }
        }
    }

    @Override
    public String toString() {
        return "Job{id=" + getIdString()
                + ", name=" + getName()
                + ", submissionTime=" + toLocalDateTime(getSubmissionTime())
                + ", status=" + getStatus()
                + "}";
    }

    /**
     * Submit and join job with a given DAG and config
     */
    protected abstract ICompletableFuture<Void> invokeSubmitJob(Data dag, JobConfig config);

    /**
     * Join already existing job
     */
    protected abstract ICompletableFuture<Void> invokeJoinJob();

    protected abstract ICompletableFuture<Void> invokeTerminateJob(TerminationMode mode);

    protected abstract long doGetJobSubmissionTime();

    protected abstract JobConfig doGetJobConfig();

    protected abstract Address masterAddress();

    protected abstract SerializationService serializationService();

    protected abstract LoggingService loggingService();

    protected T container() {
        return container;
    }

    private void doSubmitJob(DAG dag, JobConfig config) {
        CompletableFuture<Void> submitFuture = new CompletableFuture<>();
        SubmitJobCallback callback = new SubmitJobCallback(submitFuture, dag, config);
        invokeSubmitJob(serializationService().toData(dag), config).andThen(callback);
        submitFuture.join();
    }

    private boolean isRestartable(Throwable t) {
        return t instanceof MemberLeftException
                || t instanceof TargetDisconnectedException
                || t instanceof TargetNotMemberException;
    }

    private void doInvokeJoinJob() {
        invokeJoinJob().andThen(joinJobCallback);
    }

    private class SubmitJobCallback implements ExecutionCallback<Void> {
        private final CompletableFuture<Void> future;
        private final DAG dag;
        private final JobConfig config;

        SubmitJobCallback(CompletableFuture<Void> future, DAG dag, JobConfig config) {
            this.future = future;
            this.dag = dag;
            this.config = config;
        }

        @Override
        public void onResponse(Void response) {
            future.complete(null);
        }

        @Override
        public synchronized void onFailure(Throwable t) {
            Throwable ex = peel(t);
            if (ex instanceof LocalMemberResetException) {
                String msg = "Submission of job " + idAndName() + " failed because the cluster is performing " +
                        "split-brain merge";
                logger.warning(msg, ex);
                future.completeExceptionally(new CancellationException(msg));
            } else if (!isRestartable(ex)) {
                future.completeExceptionally(ex);
            } else {
                try {
                    resubmitJob(t);
                } catch (Exception e) {
                    future.completeExceptionally(peel(e));
                }
            }
        }

        private void resubmitJob(Throwable t) {
            if (masterAddress() != null) {
                logger.fine("Resubmitting job " + idAndName() + " after " + t.getClass().getSimpleName());
                invokeSubmitJob(serializationService().toData(dag), config).andThen(this);
                return;
            }
            // job data will be cleaned up eventually by coordinator
            String msg = "Job " + idAndName() + " failed because the cluster is performing "
                    + " split-brain merge and coordinator is not known";
            logger.warning(msg, t);
            future.completeExceptionally(new CancellationException(msg));
        }
    }

    private class JoinJobCallback implements ExecutionCallback<Void> {

        @Override
        public void onResponse(Void response) {
            // job completed successfully
            future.internalComplete();
        }

        @Override
        public synchronized void onFailure(Throwable t) {
            Throwable ex = peel(t);
            if (ex instanceof LocalMemberResetException) {
                String msg = "Job " + idAndName() + " failed because the cluster is performing a split-brain merge";
                logger.warning(msg, ex);
                future.internalCompleteExceptionally(new CancellationException(msg));
            } else if (!isRestartable(ex)) {
                future.internalCompleteExceptionally(ex);
            } else {
                try {
                    rejoinJob(t);
                } catch (Exception e) {
                    future.internalCompleteExceptionally(peel(e));
                }
            }
        }

        private void rejoinJob(Throwable t) {
            if (masterAddress() != null) {
                logger.fine("Rejoining to job " + idAndName() + " after " + t.getClass().getSimpleName());
                doInvokeJoinJob();
                return;
            }
            // job data will be cleaned up eventually by coordinator
            String msg = "Job " + idAndName() + " failed because the cluster is performing "
                    + " split-brain merge and coordinator is not known";
            logger.warning(msg, t);
            future.internalCompleteExceptionally(new CancellationException(msg));
        }
    }
}
