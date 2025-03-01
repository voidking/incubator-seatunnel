/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.engine.server.master;

import static com.hazelcast.jet.impl.util.ExceptionUtil.withTryCatch;

import org.apache.seatunnel.api.common.metrics.JobMetrics;
import org.apache.seatunnel.api.common.metrics.RawJobMetrics;
import org.apache.seatunnel.api.env.EnvCommonOptions;
import org.apache.seatunnel.common.utils.ExceptionUtils;
import org.apache.seatunnel.common.utils.RetryUtils;
import org.apache.seatunnel.common.utils.SeaTunnelException;
import org.apache.seatunnel.engine.common.Constant;
import org.apache.seatunnel.engine.common.config.EngineConfig;
import org.apache.seatunnel.engine.common.config.server.CheckpointConfig;
import org.apache.seatunnel.engine.common.config.server.CheckpointStorageConfig;
import org.apache.seatunnel.engine.common.exception.SeaTunnelEngineException;
import org.apache.seatunnel.engine.common.loader.SeaTunnelChildFirstClassLoader;
import org.apache.seatunnel.engine.common.utils.PassiveCompletableFuture;
import org.apache.seatunnel.engine.core.dag.logical.LogicalDag;
import org.apache.seatunnel.engine.core.job.JobDAGInfo;
import org.apache.seatunnel.engine.core.job.JobImmutableInformation;
import org.apache.seatunnel.engine.core.job.JobResult;
import org.apache.seatunnel.engine.core.job.JobStatus;
import org.apache.seatunnel.engine.server.checkpoint.CheckpointManager;
import org.apache.seatunnel.engine.server.checkpoint.CheckpointPlan;
import org.apache.seatunnel.engine.server.checkpoint.CompletedCheckpoint;
import org.apache.seatunnel.engine.server.dag.DAGUtils;
import org.apache.seatunnel.engine.server.dag.physical.PhysicalPlan;
import org.apache.seatunnel.engine.server.dag.physical.PipelineLocation;
import org.apache.seatunnel.engine.server.dag.physical.PlanUtils;
import org.apache.seatunnel.engine.server.dag.physical.SubPlan;
import org.apache.seatunnel.engine.server.execution.TaskExecutionState;
import org.apache.seatunnel.engine.server.execution.TaskGroupLocation;
import org.apache.seatunnel.engine.server.metrics.JobMetricsUtil;
import org.apache.seatunnel.engine.server.resourcemanager.ResourceManager;
import org.apache.seatunnel.engine.server.resourcemanager.resource.SlotProfile;
import org.apache.seatunnel.engine.server.scheduler.JobScheduler;
import org.apache.seatunnel.engine.server.scheduler.PipelineBaseScheduler;
import org.apache.seatunnel.engine.server.task.operation.CleanTaskGroupContextOperation;
import org.apache.seatunnel.engine.server.task.operation.GetTaskGroupMetricsOperation;
import org.apache.seatunnel.engine.server.utils.NodeEngineUtil;

import com.google.common.collect.Lists;
import com.hazelcast.cluster.Address;
import com.hazelcast.flakeidgen.FlakeIdGenerator;
import com.hazelcast.internal.serialization.Data;
import com.hazelcast.jet.datamodel.Tuple2;
import com.hazelcast.jet.impl.execution.init.CustomClassLoadedObject;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.map.IMap;
import com.hazelcast.spi.impl.NodeEngine;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class JobMaster {
    private static final ILogger LOGGER = Logger.getLogger(JobMaster.class);

    private PhysicalPlan physicalPlan;
    private final Data jobImmutableInformationData;

    private final NodeEngine nodeEngine;

    private final ExecutorService executorService;

    private final FlakeIdGenerator flakeIdGenerator;

    private final ResourceManager resourceManager;

    private final JobHistoryService jobHistoryService;

    private CheckpointManager checkpointManager;

    private CompletableFuture<JobResult> jobMasterCompleteFuture;

    private ClassLoader classLoader;

    private JobImmutableInformation jobImmutableInformation;

    private JobScheduler jobScheduler;

    private LogicalDag logicalDag;

    private JobDAGInfo jobDAGInfo;

    /**
     * we need store slot used by task in Hazelcast IMap and release or reuse it when a new master node active.
     */
    private final IMap<PipelineLocation, Map<TaskGroupLocation, SlotProfile>> ownedSlotProfilesIMap;

    private final IMap<Object, Object> runningJobStateIMap;

    private final IMap<Object, Object> runningJobStateTimestampsIMap;

    private CompletableFuture<Void> scheduleFuture;

    private volatile boolean restore = false;

    // TODO add config to change value
    private boolean isPhysicalDAGIInfo = true;

    private final EngineConfig engineConfig;

    private boolean isRunning = true;

    public JobMaster(@NonNull Data jobImmutableInformationData,
                     @NonNull NodeEngine nodeEngine,
                     @NonNull ExecutorService executorService,
                     @NonNull ResourceManager resourceManager,
                     @NonNull JobHistoryService jobHistoryService,
                     @NonNull IMap runningJobStateIMap,
                     @NonNull IMap runningJobStateTimestampsIMap,
                     @NonNull IMap ownedSlotProfilesIMap, EngineConfig engineConfig) {
        this.jobImmutableInformationData = jobImmutableInformationData;
        this.nodeEngine = nodeEngine;
        this.executorService = executorService;
        flakeIdGenerator =
            this.nodeEngine.getHazelcastInstance().getFlakeIdGenerator(Constant.SEATUNNEL_ID_GENERATOR_NAME);
        this.ownedSlotProfilesIMap = ownedSlotProfilesIMap;
        this.resourceManager = resourceManager;
        this.jobHistoryService = jobHistoryService;
        this.runningJobStateIMap = runningJobStateIMap;
        this.runningJobStateTimestampsIMap = runningJobStateTimestampsIMap;
        this.engineConfig = engineConfig;
    }

    public void init(long initializationTimestamp) throws Exception {
        jobImmutableInformation = nodeEngine.getSerializationService().toObject(
            jobImmutableInformationData);
        LOGGER.info(String.format("Init JobMaster for Job %s (%s) ", jobImmutableInformation.getJobConfig().getName(),
            jobImmutableInformation.getJobId()));
        LOGGER.info(String.format("Job %s (%s) needed jar urls %s", jobImmutableInformation.getJobConfig().getName(),
            jobImmutableInformation.getJobId(), jobImmutableInformation.getPluginJarsUrls()));

        classLoader = new SeaTunnelChildFirstClassLoader(jobImmutableInformation.getPluginJarsUrls());
        logicalDag = CustomClassLoadedObject.deserializeWithCustomClassLoader(nodeEngine.getSerializationService(),
            classLoader, jobImmutableInformation.getLogicalDag());
        CheckpointConfig checkpointConfig = mergeEnvAndEngineConfig(engineConfig.getCheckpointConfig(),
            jobImmutableInformation.getJobConfig().getEnvOptions());

        final Tuple2<PhysicalPlan, Map<Integer, CheckpointPlan>> planTuple = PlanUtils.fromLogicalDAG(logicalDag,
            nodeEngine,
            jobImmutableInformation,
            initializationTimestamp,
            executorService,
            flakeIdGenerator,
            runningJobStateIMap,
            runningJobStateTimestampsIMap,
            engineConfig.getQueueType());
        this.physicalPlan = planTuple.f0();
        this.physicalPlan.setJobMaster(this);
        this.checkpointManager = new CheckpointManager(
            jobImmutableInformation.getJobId(),
            jobImmutableInformation.isStartWithSavePoint(),
            nodeEngine,
            this,
            planTuple.f1(),
            checkpointConfig);
        this.initStateFuture();
    }

    // TODO replace it after ReadableConfig Support parse yaml format, then use only one config to read engine and env config.
    private CheckpointConfig mergeEnvAndEngineConfig(CheckpointConfig engine, Map<String, Object> env) {
        CheckpointConfig checkpointConfig = new CheckpointConfig();
        if (env.containsKey(EnvCommonOptions.CHECKPOINT_INTERVAL.key())) {
            checkpointConfig.setCheckpointInterval((Integer) env.get(EnvCommonOptions.CHECKPOINT_INTERVAL.key()));
        }
        checkpointConfig.setCheckpointTimeout(engine.getCheckpointTimeout());
        checkpointConfig.setTolerableFailureCheckpoints(engine.getTolerableFailureCheckpoints());
        checkpointConfig.setMaxConcurrentCheckpoints(engine.getMaxConcurrentCheckpoints());
        CheckpointStorageConfig storageConfig = new CheckpointStorageConfig();
        storageConfig.setMaxRetainedCheckpoints(engine.getStorage().getMaxRetainedCheckpoints());
        storageConfig.setStorage(engine.getStorage().getStorage());
        storageConfig.setStoragePluginConfig(engine.getStorage().getStoragePluginConfig());
        checkpointConfig.setStorage(storageConfig);
        return checkpointConfig;
    }

    public void initStateFuture() {
        jobMasterCompleteFuture = new CompletableFuture<>();
        PassiveCompletableFuture<JobResult> jobStatusFuture = physicalPlan.initStateFuture();
        jobStatusFuture.whenComplete(withTryCatch(LOGGER, (v, t) -> {
            // We need not handle t, Because we will not return t from physicalPlan
            if (JobStatus.FAILING.equals(v.getStatus())) {
                cleanJob();
                physicalPlan.updateJobState(JobStatus.FAILING, JobStatus.FAILED);
            }
            jobMasterCompleteFuture.complete(new JobResult(physicalPlan.getJobStatus(), v.getError()));
        }));
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    public void run() {
        try {
            if (!restore) {
                jobScheduler = new PipelineBaseScheduler(physicalPlan, this);
                scheduleFuture = CompletableFuture.runAsync(() -> jobScheduler.startScheduling(), executorService);
                LOGGER.info(String.format("Job %s waiting for scheduler finished", physicalPlan.getJobFullName()));
                scheduleFuture.join();
                LOGGER.info(String.format("%s scheduler finished", physicalPlan.getJobFullName()));
            }
        } catch (Throwable e) {
            LOGGER.severe(String.format("Job %s (%s) run error with: %s",
                physicalPlan.getJobImmutableInformation().getJobConfig().getName(),
                physicalPlan.getJobImmutableInformation().getJobId(),
                ExceptionUtils.getMessage(e)));
            // try to cancel job
            cancelJob();
        } finally {
            jobMasterCompleteFuture.join();
        }
    }

    public void handleCheckpointError(long pipelineId, Throwable e) {
        this.physicalPlan.getPipelineList().forEach(pipeline -> {
            if (pipeline.getPipelineLocation().getPipelineId() == pipelineId) {
                LOGGER.warning(
                    String.format("%s checkpoint have error, cancel the pipeline", pipeline.getPipelineFullName()), e);
                pipeline.cancelPipeline();
            }
        });
    }

    public JobDAGInfo getJobDAGInfo() {
        if (jobDAGInfo == null) {
            jobDAGInfo = DAGUtils.getJobDAGInfo(logicalDag, jobImmutableInformation, isPhysicalDAGIInfo);
        }
        return jobDAGInfo;
    }

    public PassiveCompletableFuture<Void> reSchedulerPipeline(SubPlan subPlan) {
        if (jobScheduler == null) {
            jobScheduler = new PipelineBaseScheduler(physicalPlan, this);
        }
        return new PassiveCompletableFuture<>(jobScheduler.reSchedulerPipeline(subPlan));
    }

    public void releasePipelineResource(SubPlan subPlan) {
        resourceManager.releaseResources(jobImmutableInformation.getJobId(),
            Lists.newArrayList(ownedSlotProfilesIMap.get(subPlan.getPipelineLocation()).values())).join();
        ownedSlotProfilesIMap.remove(subPlan.getPipelineLocation());
    }

    public void cleanJob() {
        // TODO Add some job clean operation
    }

    public Address queryTaskGroupAddress(long taskGroupId) {
        for (PipelineLocation pipelineLocation : ownedSlotProfilesIMap.keySet()) {
            Optional<TaskGroupLocation> currentVertex = ownedSlotProfilesIMap.get(pipelineLocation).keySet().stream()
                .filter(taskGroupLocation -> taskGroupLocation.getTaskGroupId() == taskGroupId)
                .findFirst();
            if (currentVertex.isPresent()) {
                return ownedSlotProfilesIMap.get(pipelineLocation).get(currentVertex.get()).getWorker();
            }
        }
        throw new IllegalArgumentException("can't find task group address from task group id: " + taskGroupId);
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public void cancelJob() {
        physicalPlan.neverNeedRestore();
        physicalPlan.cancelJob();
    }

    public ResourceManager getResourceManager() {
        return resourceManager;
    }

    public CheckpointManager getCheckpointManager() {
        return checkpointManager;
    }

    public PassiveCompletableFuture<JobResult> getJobMasterCompleteFuture() {
        return new PassiveCompletableFuture<>(jobMasterCompleteFuture);
    }

    public JobImmutableInformation getJobImmutableInformation() {
        return jobImmutableInformation;
    }

    public JobStatus getJobStatus() {
        return physicalPlan.getJobStatus();
    }

    public List<RawJobMetrics> getCurrJobMetrics() {
        return getCurrJobMetrics(ownedSlotProfilesIMap.values());
    }

    public List<RawJobMetrics> getCurrJobMetrics(Collection<Map<TaskGroupLocation, SlotProfile>> groupLocations) {
        List<RawJobMetrics> metrics = new ArrayList<>();
        for (Map<TaskGroupLocation, SlotProfile> groupLocation : groupLocations) {
            groupLocation.forEach((taskGroupLocation, slotProfile) -> {
                if (taskGroupLocation.getJobId() == this.getJobImmutableInformation().getJobId()) {
                    try {
                        RawJobMetrics rawJobMetrics = (RawJobMetrics) NodeEngineUtil.sendOperationToMemberNode(nodeEngine,
                            new GetTaskGroupMetricsOperation(taskGroupLocation), slotProfile.getWorker()).get();
                        metrics.add(rawJobMetrics);
                    } catch (Exception e) {
                        throw new SeaTunnelException(e.getMessage());
                    }
                }
            });
        }
        return metrics;
    }

    public void savePipelineMetricsToHistory(PipelineLocation pipelineLocation) {
        List<RawJobMetrics> currJobMetrics = this.getCurrJobMetrics(Collections.singleton(this.getOwnedSlotProfiles(pipelineLocation)));
        JobMetrics jobMetrics = JobMetricsUtil.toJobMetrics(currJobMetrics);
        long jobId = this.getJobImmutableInformation().getJobId();
        synchronized (this) {
            jobHistoryService.storeFinishedPipelineMetrics(jobId, jobMetrics);
        }
        //Clean TaskGroupContext for TaskExecutionServer
        this.cleanTaskGroupContext(pipelineLocation);
    }

    private void cleanTaskGroupContext(PipelineLocation pipelineLocation) {
        ownedSlotProfilesIMap.get(pipelineLocation).forEach((taskGroupLocation, slotProfile) -> {
            try {
                NodeEngineUtil.sendOperationToMemberNode(nodeEngine,
                    new CleanTaskGroupContextOperation(taskGroupLocation), slotProfile.getWorker()).get();
            } catch (Exception e) {
                throw new SeaTunnelException(e.getMessage());
            }
        });
    }

    public PhysicalPlan getPhysicalPlan() {
        return physicalPlan;
    }

    public void updateTaskExecutionState(TaskExecutionState taskExecutionState) {
        this.physicalPlan.getPipelineList().forEach(pipeline -> {
            if (pipeline.getPipelineLocation().getPipelineId() !=
                taskExecutionState.getTaskGroupLocation().getPipelineId()) {
                return;
            }

            pipeline.getCoordinatorVertexList().forEach(task -> {
                if (!task.getTaskGroupLocation().equals(taskExecutionState.getTaskGroupLocation())) {
                    return;
                }

                task.updateTaskExecutionState(taskExecutionState);
            });

            pipeline.getPhysicalVertexList().forEach(task -> {
                if (!task.getTaskGroupLocation().equals(taskExecutionState.getTaskGroupLocation())) {
                    return;
                }

                task.updateTaskExecutionState(taskExecutionState);
            });
        });
    }

    /**
     * Execute savePoint, which will cause the job to end.
     */
    public CompletableFuture<Void> savePoint(){
        PassiveCompletableFuture<CompletedCheckpoint>[] passiveCompletableFutures =
            checkpointManager.triggerSavepoints();
        return CompletableFuture.allOf(passiveCompletableFutures);
    }

    public Map<TaskGroupLocation, SlotProfile> getOwnedSlotProfiles(PipelineLocation pipelineLocation) {
        return ownedSlotProfilesIMap.get(pipelineLocation);
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    public void setOwnedSlotProfiles(@NonNull PipelineLocation pipelineLocation,
                                     @NonNull Map<TaskGroupLocation, SlotProfile> pipelineOwnedSlotProfiles) {
        ownedSlotProfilesIMap.put(pipelineLocation, pipelineOwnedSlotProfiles);
        try {
            RetryUtils.retryWithException(() -> pipelineOwnedSlotProfiles.equals(ownedSlotProfilesIMap.get(pipelineLocation)),
                new RetryUtils.RetryMaterial(20, true,
                    exception -> exception instanceof NullPointerException && isRunning, 1000));
        } catch (Exception e) {
            throw new SeaTunnelEngineException("Can not sync pipeline owned slot profiles with IMap", e);
        }
    }

    public SlotProfile getOwnedSlotProfiles(@NonNull TaskGroupLocation taskGroupLocation) {
        return ownedSlotProfilesIMap.get(
                new PipelineLocation(taskGroupLocation.getJobId(), taskGroupLocation.getPipelineId()))
            .get(taskGroupLocation);
    }

    public CompletableFuture<Void> getScheduleFuture() {
        return scheduleFuture;
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    public void interrupt() {
        isRunning = false;
        jobMasterCompleteFuture.cancel(true);
    }

    public void markRestore() {
        restore = true;
    }
}
