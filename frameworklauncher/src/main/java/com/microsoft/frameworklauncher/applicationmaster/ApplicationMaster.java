// Copyright (c) Microsoft Corporation
// All rights reserved. 
//
// MIT License
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated 
// documentation files (the "Software"), to deal in the Software without restriction, including without limitation 
// the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and 
// to permit persons to whom the Software is furnished to do so, subject to the following conditions:
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING 
// BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND 
// NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, 
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, 
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE. 

package com.microsoft.frameworklauncher.applicationmaster;

import com.microsoft.frameworklauncher.common.LauncherClientInternal;
import com.microsoft.frameworklauncher.common.WebCommon;
import com.microsoft.frameworklauncher.common.exceptions.AggregateException;
import com.microsoft.frameworklauncher.common.exceptions.NonTransientException;
import com.microsoft.frameworklauncher.common.exceptions.NotAvailableException;
import com.microsoft.frameworklauncher.common.model.*;
import com.microsoft.frameworklauncher.hdfsstore.HdfsStore;
import com.microsoft.frameworklauncher.utils.*;
import com.microsoft.frameworklauncher.zookeeperstore.ZookeeperStore;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.client.api.async.NMClientAsync;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Records;
import org.apache.log4j.Level;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

// Maintains the life cycle for one Framework owned by this AM.
// It is the engine to transition Status to satisfy Request eventually.
// It is designed as a micro kernel to connect all its SubServices.
// Note:
//  It does NOT ensure at most one Container running for one Task, but eventually it should be.
public class ApplicationMaster extends AbstractService {
  private static final DefaultLogger LOGGER = new DefaultLogger(ApplicationMaster.class);
  private static final ChangeAwareLogger CHANGE_AWARE_LOGGER = new ChangeAwareLogger(ApplicationMaster.class);

  protected Configuration conf = new Configuration();
  protected SystemTaskQueue transitionTaskStateQueue;

  /**
   * REGION SubServices
   */
  protected ZookeeperStore zkStore;
  protected HdfsStore hdfsStore;
  protected YarnClient yarnClient;
  protected LauncherClientInternal launcherClient;
  protected AMRMClientAsync<ContainerRequest> rmClient;
  // Note we should only use nmClient to start container, and leave other Container
  // managements to rmClient to ensure AM RM timely synced.
  protected NMClientAsync nmClient;
  protected StatusManager statusManager;
  protected RequestManager requestManager;
  private RMResyncHandler rmResyncHandler;
  protected AntiaffinityAllocationManager aaAllocationManager;
  private GpuAllocationManager gpuAllocationManager;

  /**
   * REGION StateVariable
   */
  private int rmClientFailureCount = 0;

  // Note:
  //  1. It should only be used to launchContainersTogether.
  //  2. It cannot be recovered after AM Restart. However, it will not cause
  //  previous Allocated Container not found in it when launchContainersTogether,
  //  since previous Allocated Container is already Transitioned to Running
  //  before launchContainersTogether and will be Transitioned to Completed due
  //  to expire. So the only impact is longer time to launchContainersTogether.
  private Map<String, Container> allocatedContainers = new HashMap<>();
  private Map<String, Integer> containerConnectionExceedCount = new HashMap<>();

  /**
   * REGION AbstractService
   */
  public ApplicationMaster() {
    super(ApplicationMaster.class.getName());
  }

  @Override
  protected Boolean handleException(Exception e) {
    super.handleException(e);

    if (e instanceof NonTransientException) {
      String msg = String.format(
          "NonTransientException occurred in %s. Framework will be stopped.",
          serviceName);
      LOGGER.logError(e, msg);
      msg += CommonUtils.toString(e);

      stopForInternalNonTransientError(msg);
      return false;
    } else {
      String msg = String.format(
          "Exception occurred in %1$s. It should be transient. Will migrate %1$s to another node.",
          serviceName);
      LOGGER.logError(e, msg);
      msg += CommonUtils.toString(e);

      stopForInternalTransientError(msg);
      return false;
    }
  }

  @Override
  protected void initialize() throws Exception {
    super.initialize();
    transitionTaskStateQueue = new SystemTaskQueue(this::handleException);

    // Initialize AM NoDependenceConfig
    conf.initializeNoDependenceConfig();

    // Start RMClient to Register AM ASAP in case AM expired by RM
    rmClient = AMRMClientAsync.createAMRMClientAsync(
        conf.getAmRmHeartbeatIntervalSec() * 1000,
        new RMClientCallbackHandler(this));
    rmClient.init(conf.getYarnConfig());
    rmClient.start();
    conf.initializeDependOnRMResponseConfig(registerToRM());

    // Start NMClient
    nmClient = NMClientAsync.createNMClientAsync(
        new NMClientCallbackHandler(this));
    nmClient.init(conf.getYarnConfig());
    nmClient.start();

    // Start YarnClient
    yarnClient = YarnClient.createYarnClient();
    yarnClient.init(conf.getYarnConfig());
    yarnClient.start();
    conf.initializeDependOnYarnClientConfig(yarnClient);

    // Initialize Launcher Store
    zkStore = new ZookeeperStore(conf.getZkConnectString(), conf.getZkRootDir());
    conf.initializeDependOnZKStoreConfig(zkStore);
    hdfsStore = new HdfsStore(conf.getLauncherConfig().getHdfsRootDir());
    hdfsStore.makeFrameworkRootDir(conf.getFrameworkName());
    hdfsStore.makeAMStoreRootDir(conf.getFrameworkName());

    // Initialize other components
    launcherClient = new LauncherClientInternal(
        conf.getLauncherConfig().getWebServerAddress(), 30, 10,
        LaunchClientType.APPLICATION_MASTER);

    aaAllocationManager = new AntiaffinityAllocationManager();
    gpuAllocationManager = new GpuAllocationManager(this);
    rmResyncHandler = new RMResyncHandler(this, conf);
  }

  @Override
  protected void recover() throws Exception {
    super.recover();
    statusManager = new StatusManager(this, conf, zkStore);
    statusManager.start();

    // Here StatusManager recover completed
    reviseCorruptedTaskStates();
    recoverTransitionTaskStateQueue();
  }

  @Override
  protected void run() throws Exception {
    super.run();
    requestManager = new RequestManager(this, conf, zkStore, launcherClient);
    requestManager.start();
  }

  // THREAD SAFE
  @Override
  public synchronized void stop(StopStatus stopStatus) {
    // Best Effort to stop Gracefully
    super.stop(stopStatus);

    AggregateException ae = new AggregateException();

    // Stop AM's SubServices
    // No need to stop nmClient, since it may be time consuming to stop all Containers, leave it for RM.
    // Since here is Best Effort, leave the GC work of zkStore and hdfsStore to LauncherService.
    try {
      if (yarnClient != null) {
        yarnClient.stop();
      }
    } catch (Exception e) {
      ae.addException(e);
    }

    try {
      if (statusManager != null) {
        statusManager.stop(stopStatus);
      }
    } catch (Exception e) {
      ae.addException(e);
    }

    try {
      if (requestManager != null) {
        requestManager.stop(stopStatus);
      }
    } catch (Exception e) {
      ae.addException(e);
    }

    // Stop rmClient at last, since there is no work left in current AM, and only then RM is
    // allowed to process the application, such as generate application's diagnostics.
    try {
      if (rmClient != null) {
        if (stopStatus.getNeedUnregister()) {
          LOGGER.logInfo("Unregistering %s to RM", serviceName);
          rmClient.unregisterApplicationMaster(
              stopStatus.getCode() == 0 ?
                  FinalApplicationStatus.SUCCEEDED :
                  FinalApplicationStatus.FAILED,
              stopStatus.getDiagnostics(), conf.getAmTrackingUrl());
        }
        rmClient.stop();
      }
    } catch (Exception e) {
      ae.addException(e);
    }

    if (ae.getExceptions().size() > 0) {
      LOGGER.logWarning(ae, "Failed to stop %s gracefully", serviceName);
    }

    LOGGER.logInfo("%s stopped", serviceName);
    System.exit(stopStatus.getCode());
  }

  /**
   * REGION InternalUtils
   */
  private RegisterApplicationMasterResponse registerToRM() throws Exception {
    LOGGER.logInfo("Registering %s to RM", serviceName);
    RegisterApplicationMasterResponse rmResp =
        rmClient.registerApplicationMaster(conf.getAmHostName(), conf.getAmRpcPort(), conf.getAmTrackingUrl());

    // Dump out Response from RM
    LOGGER.logInfo("Running Framework [%s] in Queue [%s]", conf.getFrameworkName(), rmResp.getQueue());
    // No need to use it, since we can get the it from RM Resync
    LOGGER.logInfo("Got %s Containers from previous attempts", rmResp.getContainersFromPreviousAttempts().size());
    return rmResp;
  }

  private void stopForContainer(int exitCode) {
    stopForContainer(exitCode, "");
  }

  private void stopForContainer(int exitCode, String diagnostics) {
    stopForContainer(exitCode, diagnostics, "");
  }

  private void stopForContainer(int exitCode, String diagnostics, String customizedDiagnostics) {
    ExitStatusValue partialValue = new ExitStatusValue(exitCode, diagnostics, ExitType.NOT_AVAILABLE);

    String fullDiagnostics = DiagnosticsUtils.generateDiagnostics(partialValue, customizedDiagnostics);
    ExitStatusKey exitStatusKey = DiagnosticsUtils.extractExitStatusKey(fullDiagnostics);

    stop(new StopStatus(exitStatusKey.toInt(), true, fullDiagnostics));
  }

  private void stopForInternalTransientError() {
    stopForInternalTransientError("");
  }

  private void stopForInternalTransientError(String customizedDiagnostics) {
    String diagnostics = DiagnosticsUtils.generateDiagnostics(
        ExitStatusKey.AM_INTERNAL_TRANSIENT_ERROR, customizedDiagnostics);

    // Do not unregister, so that RM will start new attempt if AMAttemptMaxCount and
    // AMAttemptFailuresValidityIntervalSec is allowed.
    stop(new StopStatus(ExitStatusKey.AM_INTERNAL_TRANSIENT_ERROR.toInt(), false, diagnostics));
  }

  private void stopForInternalNonTransientError() {
    stopForInternalNonTransientError("");
  }

  private void stopForInternalNonTransientError(String customizedDiagnostics) {
    String diagnostics = DiagnosticsUtils.generateDiagnostics(
        ExitStatusKey.AM_INTERNAL_NON_TRANSIENT_ERROR, customizedDiagnostics);

    stop(new StopStatus(ExitStatusKey.AM_INTERNAL_NON_TRANSIENT_ERROR.toInt(), true, diagnostics));
  }

  private void stopForInternalUnKnownError() {
    stopForInternalUnKnownError("");
  }

  private void stopForInternalUnKnownError(String customizedDiagnostics) {
    String diagnostics = DiagnosticsUtils.generateDiagnostics(
        ExitStatusKey.AM_INTERNAL_UNKNOWN_ERROR, customizedDiagnostics);

    stop(new StopStatus(ExitStatusKey.AM_INTERNAL_UNKNOWN_ERROR.toInt(), true, diagnostics));
  }

  private ContainerRequest setupContainerRequest(TaskStatus taskStatus) throws Exception {
    String taskRoleName = taskStatus.getTaskRoleName();
    String nodeLabel = requestManager.getTaskPlatParams().get(taskRoleName).getTaskNodeLabel();
    String nodeGpuType = requestManager.getTaskPlatParams().get(taskRoleName).getTaskNodeGpuType();
    Boolean aaAllocation = requestManager.getPlatParams().getAntiaffinityAllocation();
    ResourceDescriptor resource = requestManager.getTaskResources().get(taskRoleName);
    Integer priority = requestManager.getTaskRoles().get(taskRoleName).getPriority();
    Resource maxResource = conf.getMaxResource();
    ResourceDescriptor maxRes = ResourceDescriptor.fromResource(maxResource);

    if (resource.getMemoryMB() > maxRes.getMemoryMB() ||
        resource.getCpuNumber() > maxRes.getCpuNumber() ||
        resource.getGpuNumber() > maxRes.getGpuNumber()) {
      LOGGER.logWarning(
          "Detected the Resource to be Requested is larger than the max Resource configured in current cluster. " +
              "Request may be fail or never got satisfied. " +
              "RequestedResource %s, MaxResource %s",
          resource.toString(), maxRes.toString());
    }

    if (resource.getGpuNumber() > 0) {
      // Used to workaround for bug YARN-314.
      // We need to make sure the Priority for each different GPU request are also different.
      priority = (priority << 16) + taskStatus.getTaskIndex();

      List<NodeReport> nodeReport = yarnClient.getNodeReports(NodeState.RUNNING);
      updateNodeReport(nodeReport, resource);

      Node candidateRequestNode = gpuAllocationManager.allocateCandidateRequestNode(resource, nodeLabel, nodeGpuType);
      if (candidateRequestNode != null) {
        taskStatus.setContainerGpus(candidateRequestNode.getSelectedGpuBitmap());
        // The original resource doesn't contain the real time gpuAttribute information,
        // We need set the candidate gpuAttribute into container request resource.
        ResourceDescriptor resourceWithGpuAttribute = ResourceDescriptor.newInstance(
            resource.getMemoryMB(), resource.getCpuNumber(), resource.getGpuNumber(), candidateRequestNode.getSelectedGpuBitmap());
        return HadoopUtils.convertToContainerRequestWithHostName(resourceWithGpuAttribute, priority, candidateRequestNode.getHostName());
      } else {
        LOGGER.logWarning("No candidate request HostNames. Will request without HostName and Any GPUs topology");
        taskStatus.setContainerGpus(0L);
        return HadoopUtils.convertToContainerRequestWithNodeLabel(resource, priority, nodeLabel);
      }
    }

    if (nodeLabel != null) {
      return HadoopUtils.convertToContainerRequestWithNodeLabel(resource, priority, nodeLabel);
    } else {
      if (aaAllocation) {
        String candidateRequestHostName = aaAllocationManager.getCandidateRequestHostName();
        if (candidateRequestHostName != null) {
          return HadoopUtils.convertToContainerRequestWithHostName(resource, priority, candidateRequestHostName);
        } else {
          LOGGER.logWarning(
              "No candidate request HostNames. Will request without HostName and wait HostNames to be released");
        }
      }
      return HadoopUtils.convertToContainerRequest(resource, priority);
    }
  }

  private ContainerRequest setupContainerRequest(Container container) throws Exception {
    return HadoopUtils.convertToContainerRequest(container);
  }

  private String generateContainerDiagnostics(TaskStatus taskStatus) {
    return generateContainerDiagnostics(taskStatus, "");
  }

  private String generateContainerDiagnostics(TaskStatus taskStatus, String linePrefix) {
    String containerId = taskStatus.getContainerId();
    String hostName = taskStatus.getContainerHost();
    String logHttpAddress = taskStatus.getContainerLogHttpAddress();

    return String.format(
        "%4$sContainerLogHttpAddress: %1$s\n" +
            "%4$sAppCacheNetworkPath: %2$s\n" +
            "%4$sContainerLogNetworkPath: %3$s",
        logHttpAddress,
        HadoopUtils.getAppCacheNetworkPath(hostName, conf.getAmLocalDirs()),
        HadoopUtils.getContainerLogNetworkPath(hostName, conf.getAmLogDirs(), containerId),
        linePrefix);
  }

  private String generateCustomizedDiagnosticsPrefix(TaskStatus taskStatus) throws IOException {
    String taskRoleName = taskStatus.getTaskRoleName();
    TaskStatusLocator taskLocator = new TaskStatusLocator(taskRoleName, taskStatus.getTaskIndex());
    String containerId = taskStatus.getContainerId();
    String hostName = taskStatus.getContainerHost();

    String customizedDiagnosticsPrefix = "";
    customizedDiagnosticsPrefix += String.format("%s: TASK_COMPLETED:" +
        "\n[TaskStatus]:\n%s", taskLocator, WebCommon.toJson(taskStatus));

    customizedDiagnosticsPrefix += String.format(
        "\n[ContainerDiagnostics]:" +
            "\nContainer completed %s on HostName %s." +
            "\n%s", containerId, hostName, generateContainerDiagnostics(taskStatus));

    customizedDiagnosticsPrefix += String.format(
        "\n%s\n[AMStopReason]:", GlobalConstants.LINE);

    return customizedDiagnosticsPrefix;
  }

  private void attemptToStop(TaskStatus taskStatus) throws IOException {
    String taskRoleName = taskStatus.getTaskRoleName();
    TaskStatusLocator taskLocator = new TaskStatusLocator(taskRoleName, taskStatus.getTaskIndex());
    String diagnostics = taskStatus.getContainerExitDiagnostics();
    Integer exitCode = taskStatus.getContainerExitCode();
    ExitType exitType = taskStatus.getContainerExitType();
    String containerId = taskStatus.getContainerId();
    String hostName = taskStatus.getContainerHost();
    Boolean generateContainerIpList = requestManager.getPlatParams().getGenerateContainerIpList();
    Boolean killAllOnAnyCompleted = requestManager.getPlatParams().getKillAllOnAnyCompleted();
    Boolean killAllOnAnyServiceCompleted = requestManager.getPlatParams().getKillAllOnAnyServiceCompleted();

    if (generateContainerIpList && exitCode == ExitStatusKey.CONTAINER_START_FAILED.toInt()) {
      String customizedDiagnostics = generateCustomizedDiagnosticsPrefix(taskStatus) + String.format(
          "Failed to start Container %s on HostName %s, and GenerateContainerIpList enabled.",
          containerId, hostName);
      stopForContainer(exitCode, diagnostics, customizedDiagnostics);
    }

    if (killAllOnAnyCompleted) {
      String customizedDiagnostics = generateCustomizedDiagnosticsPrefix(taskStatus) + String.format(
          "Task %s Completed and KillAllOnAnyCompleted enabled.",
          taskLocator);
      stopForContainer(exitCode, diagnostics, customizedDiagnostics);
    }

    if (killAllOnAnyServiceCompleted) {
      // Consider these exitTypes and exitCodes are indicated UserService exit
      if (exitType == ExitType.NOT_AVAILABLE ||
          exitType == ExitType.SUCCEEDED ||
          exitType == ExitType.UNKNOWN ||
          exitCode == ExitStatusKey.USER_APP_TRANSIENT_ERROR.toInt() ||
          exitCode == ExitStatusKey.USER_APP_NON_TRANSIENT_ERROR.toInt()) {
        String customizedDiagnostics = generateCustomizedDiagnosticsPrefix(taskStatus) + String.format(
            "Task %s Completed with ExitType %s and KillAllOnAnyServiceCompleted enabled.",
            taskLocator, exitType);
        stopForContainer(exitCode, diagnostics, customizedDiagnostics);
      }
    }

    if (statusManager.isAllTaskInFinalState()) {
      int totalTaskCount = statusManager.getTaskCount();
      List<TaskStatus> failedTaskStatuses = statusManager.getFailedTaskStatus();
      String customizedDiagnosticsSuffix = String.format(
          "All Tasks are in FINAL_STATES. TotalTaskCount: %s, FailedTaskCount: %s",
          totalTaskCount, failedTaskStatuses.size());

      if (failedTaskStatuses.size() > 0) {
        TaskStatus lastFailedTaskStatus = failedTaskStatuses.get(0);
        for (TaskStatus failedTaskStatus : failedTaskStatuses) {
          if (lastFailedTaskStatus.getTaskCompletedTimestamp() < failedTaskStatus.getTaskCompletedTimestamp()) {
            lastFailedTaskStatus = failedTaskStatus;
          }
        }
        String customizedDiagnostics = generateCustomizedDiagnosticsPrefix(lastFailedTaskStatus) + customizedDiagnosticsSuffix;
        stopForContainer(lastFailedTaskStatus.getContainerExitCode(), lastFailedTaskStatus.getContainerExitDiagnostics(), customizedDiagnostics);
      } else {
        String customizedDiagnostics = generateCustomizedDiagnosticsPrefix(taskStatus) + customizedDiagnosticsSuffix;
        stopForContainer(exitCode, diagnostics, customizedDiagnostics);
      }
    }
  }

  // Only can be used in completeContainer, onTaskToRemove or to release a not live associated Container.
  // Should use completeContainer to release a live associated Container or need to log
  // the diagnostics of a Container.
  private Boolean tryToReleaseContainer(String containerId) {
    try {
      LOGGER.logDebug("[%s]: releaseAssignedContainer", containerId);
      rmClient.releaseAssignedContainer(ConverterUtils.toContainerId(containerId));
      return true;
    } catch (Exception e) {
      LOGGER.logError(e, "[%s]: Failed to releaseAssignedContainer", containerId);
      return false;
    }
  }

  private float getApplicationProgress() throws Exception {
    String requestManagerLogScope = "RequestManager_GetApplicationProgress";
    String statusManagerLogScope = "StatusManager_GetApplicationProgress";
    CHANGE_AWARE_LOGGER.initializeScope(requestManagerLogScope, Level.DEBUG);
    CHANGE_AWARE_LOGGER.initializeScope(statusManagerLogScope, Level.DEBUG);

    try {
      return requestManager.getApplicationProgress();
    } catch (Exception reqEx) {
      CHANGE_AWARE_LOGGER.log(requestManagerLogScope,
          "Failed to getApplicationProgress from RequestManager.%s",
          CommonUtils.toString(reqEx));

      try {
        return statusManager.getApplicationProgress();
      } catch (Exception statEx) {
        CHANGE_AWARE_LOGGER.log(statusManagerLogScope,
            "Failed to getApplicationProgress from StatusManager. Return 0 Progress.%s",
            CommonUtils.toString(reqEx));
        return 0;
      }
    }
  }

  private TaskStatus findTask(Container container) throws Exception {
    List<TaskStatus> taskStatuses = statusManager.getTaskStatus(
        new HashSet<>(Collections.singletonList(TaskState.CONTAINER_REQUESTED)));

    // Higher Priority for Lower TaskIndex, since updateTaskNumbers update tail Tasks firstly.
    taskStatuses.sort(Comparator.comparing(TaskStatus::getTaskIndex));
    for (TaskStatus taskStatus : taskStatuses) {
      String taskRoleName = taskStatus.getTaskRoleName();
      ResourceDescriptor resourceDescriptor = requestManager.getTaskResources().get(taskRoleName);

      Integer priority = requestManager.getTaskRoles().get(taskRoleName).getPriority();
      if (resourceDescriptor.getGpuNumber() > 0) {
        // Used to workaround for bug YARN-314.
        // We need to make sure the Priority for each different GPU request are also different
        priority = (priority << 16) + taskStatus.getTaskIndex();
      }

      Long containerGpuAttribute = ResourceDescriptor.fromResource(container.getResource()).getGpuAttribute();
      if (HadoopExtensions.equals(resourceDescriptor.toResource(), container.getResource()) &&
          HadoopExtensions.equals(HadoopExtensions.toPriority(priority), container.getPriority()) &&
          (containerGpuAttribute == 0 || taskStatus.getContainerGpus() == 0 ||
              taskStatus.getContainerGpus().longValue() == containerGpuAttribute.longValue())) {
        return taskStatus;
      }
    }
    return null;
  }

  private Boolean testContainerNode(String containerId, String containerHostName) {
    String logPrefix = String.format("[%s][%s]: testContainerNode: ", containerId, containerHostName);
    String rejectedLogPrefix = logPrefix + "Rejected: ";
    String acceptedLogPrefix = logPrefix + "Accepted: ";

    Boolean aaAllocation = requestManager.getPlatParams().getAntiaffinityAllocation();
    if (aaAllocation) {
      if (statusManager.isHostNameLiveAssociated(containerHostName)) {
        LOGGER.logWarning(rejectedLogPrefix + "Node is not an antiaffinity allocation.");
        return false;
      } else {
        LOGGER.logInfo(acceptedLogPrefix + "Node is an antiaffinity allocation.");
      }
    }

    return true;
  }

  private Boolean testContainer(Container container) {
    String containerId = container.getId().toString();
    String containerHostName = container.getNodeId().getHost();

    if (!testContainerNode(containerId, containerHostName)) {
      return false;
    }

    return true;
  }

  private ContainerLaunchContext setupContainerLaunchContext(TaskStatus taskStatus) throws Exception {
    String taskRoleName = taskStatus.getTaskRoleName();
    Integer taskIndex = taskStatus.getTaskIndex();
    Integer serviceVersion = getServiceVersion(taskRoleName);

    UserDescriptor user = requestManager.getUser();
    Boolean generateContainerIpList = requestManager.getPlatParams().getGenerateContainerIpList();
    List<String> sourceLocations = requestManager.getTaskServices().get(taskRoleName).getSourceLocations();
    String entryPoint = requestManager.getTaskServices().get(taskRoleName).getEntryPoint();

    // SetupLocalResources
    Map<String, LocalResource> localResources = new HashMap<>();
    try {
      for (String location : sourceLocations) {
        HadoopUtils.addToLocalResources(localResources, location);
      }
    } catch (Exception e) {
      // User is likely to set an invalid SourceLocations, and it contains HDFS OP,
      // so handle the corresponding Exception ASAP
      handleException(e);
    }

    if (generateContainerIpList) {
      String location = hdfsStore.getHdfsStruct().getContainerIpListFilePath(conf.getFrameworkName());
      HadoopUtils.addToLocalResources(localResources, location);
    }

    // SetupLocalEnvironment
    Map<String, String> localEnvs = new HashMap<>();
    localEnvs.put(GlobalConstants.ENV_VAR_HADOOP_USER_NAME, user.getName());

    localEnvs.put(GlobalConstants.ENV_VAR_FRAMEWORK_NAME, conf.getFrameworkName());
    localEnvs.put(GlobalConstants.ENV_VAR_FRAMEWORK_VERSION, conf.getFrameworkVersion().toString());
    localEnvs.put(GlobalConstants.ENV_VAR_TASK_ROLE_NAME, taskRoleName);
    localEnvs.put(GlobalConstants.ENV_VAR_TASK_INDEX, taskIndex.toString());
    localEnvs.put(GlobalConstants.ENV_VAR_SERVICE_VERSION, serviceVersion.toString());

    localEnvs.put(GlobalConstants.ENV_VAR_ZK_CONNECT_STRING, conf.getZkConnectString());
    localEnvs.put(GlobalConstants.ENV_VAR_ZK_ROOT_DIR, conf.getZkRootDir());
    localEnvs.put(GlobalConstants.ENV_VAR_AM_VERSION, conf.getAmVersion().toString());
    localEnvs.put(GlobalConstants.ENV_VAR_APP_ID, conf.getApplicationId());
    localEnvs.put(GlobalConstants.ENV_VAR_ATTEMPT_ID, conf.getAttemptId());

    localEnvs.put(GlobalConstants.ENV_VAR_CONTAINER_GPUS, Long.toBinaryString(taskStatus.getContainerGpus()));

    if (generateContainerIpList) {
      // Since one machine may have many external IPs, we assigned a specific one to
      // help the UserService to locate itself in CONTAINER_IP_LIST_FILE
      localEnvs.put(GlobalConstants.ENV_VAR_CONTAINER_IP, taskStatus.getContainerIp());
    }


    // SetupEntryPoint
    String command = String.format(
        "%1$s 1>%2$sstdout 2>%2$sstderr",
        entryPoint,
        ApplicationConstants.LOG_DIR_EXPANSION_VAR + File.separator);

    ContainerLaunchContext launchContext = Records.newRecord(ContainerLaunchContext.class);
    launchContext.setLocalResources(localResources);
    launchContext.setCommands(Collections.singletonList(command));
    launchContext.setServiceData(new HashMap<>());
    launchContext.setEnvironment(localEnvs);

    return launchContext;
  }

  private void updateAntiaffinityAllocation(List<String> accessibleHostNames) {
    Boolean aaAllocation = requestManager.getPlatParams().getAntiaffinityAllocation();
    if (aaAllocation) {
      List<String> liveAssociatedHostNames = statusManager.getLiveAssociatedHostNames();
      LOGGER.logInfo("updateAntiaffinityAllocation: Current LiveAssociatedHostNames: %s, AccessibleHostNames: %s",
          liveAssociatedHostNames.size(), accessibleHostNames.size());

      List<String> candidateRequestHostNames = new ArrayList<>(accessibleHostNames);
      candidateRequestHostNames.removeAll(liveAssociatedHostNames);
      aaAllocationManager.updateCandidateRequestHostNames(candidateRequestHostNames);
    }
  }

  private void updateNodeReport(List<NodeReport> nodeReports) throws Exception {
    updateNodeReport(nodeReports, ResourceDescriptor.newInstance(0, 0, 0, 0L));
  }

  private void updateNodeReport(List<NodeReport> nodeReports, ResourceDescriptor requestedResource) throws Exception {
    Boolean aaAllocation = requestManager.getPlatParams().getAntiaffinityAllocation();

    for (NodeReport nodeReport : nodeReports) {
      String hostName = nodeReport.getNodeId().getHost();
      NodeState state = nodeReport.getNodeState();
      Set<String> labelSet = nodeReport.getNodeLabels();

      Node node = new Node(hostName, labelSet,
          ResourceDescriptor.fromResource(nodeReport.getCapability()),
          ResourceDescriptor.fromResource(nodeReport.getUsed()));

      if (state == NodeState.RUNNING ||
          state == NodeState.NEW) {
        if (aaAllocation) {
          aaAllocationManager.addCandidateRequestHostName(hostName);
        }
        if (requestedResource.getGpuNumber() > 0) {
          gpuAllocationManager.addCandidateRequestNode(node);
        }
      } else if (
          state == NodeState.DECOMMISSIONED ||
              state == NodeState.LOST ||
              state == NodeState.UNHEALTHY) {
        if (aaAllocation) {
          aaAllocationManager.removeCandidateRequestHostName(hostName);
        }
        if (requestedResource.getGpuNumber() > 0) {
          gpuAllocationManager.removeCandidateRequestNode(node);
        }
      }
    }
  }

  /**
   * REGION TaskStateMachine
   */
  // Method which will cause transitionTaskState
  // Note they should be called in single thread, such as from transitionTaskStateQueue

  // Should be called after StatusManager recover completed
  private void reviseCorruptedTaskStates()// throws Exception
  {
    LOGGER.logInfo(
        "reviseCorruptedTaskStates: %s",
        CommonExtensions.toString(TaskStateDefinition.STATE_CORRUPTED_AFTER_RESTART_STATES));

    List<TaskStatus> corruptedTaskStatuses = statusManager.getTaskStatus(
        TaskStateDefinition.STATE_CORRUPTED_AFTER_RESTART_STATES);
    try {
      for (TaskStatus taskStatus : corruptedTaskStatuses) {
        TaskState taskState = taskStatus.getTaskState();
        TaskStatusLocator taskLocator = new TaskStatusLocator(taskStatus.getTaskRoleName(), taskStatus.getTaskIndex());

        // Previous Requested Container may not receive onContainersAllocated after AM Restart
        if (taskState == TaskState.CONTAINER_REQUESTED) {
          statusManager.transitionTaskState(taskLocator, TaskState.TASK_WAITING);
        }

        // Previous Allocated Container will lost the Container object to Launch after AM Restart
        // Previous Launched Container may not receive onContainerStarted after AM Restart
        // Because misjudge a ground truth Running Container to be TASK_WAITING (lose Task) is more serious than
        // misjudge a ground truth not Running Container to Running. (The misjudged Container will be expired
        // by RM eventually, so the only impact is longger time to run all Tasks)
        if (taskState == TaskState.CONTAINER_ALLOCATED ||
            taskState == TaskState.CONTAINER_LAUNCHED) {
          statusManager.transitionTaskState(taskLocator, TaskState.CONTAINER_RUNNING);
        }
      }
    } catch (Exception e) {
      LOGGER.logError(e, "Revise corrupted task states failed.");
    }
  }

  private void recoverTransitionTaskStateQueue() {
    // No need to recover TransitionTaskStateQueue for:
    // 1. STATE_CORRUPTED_AFTER_RESTART_STATES, since they are revised to other States by reviseCorruptedTaskStates
    // 2. CONTAINER_RUNNING, since it can be handled by RMResyncHandler and RMClientCallbackHandler
    // 3. TASK_COMPLETED, since it is FinalState
    LOGGER.logInfo(
        "recoverTransitionTaskStateQueue for TaskState: %s",
        CommonExtensions.toString(TaskStateDefinition.QUEUE_CORRUPTED_AFTER_RESTART_STATES));

    // There may be a lot of corrupted SystemTasks, so we queue them as one SystemTask per State
    transitionTaskStateQueue.queueSystemTask(() -> {
      addContainerRequest();
    });
    LOGGER.logInfo("All the previous TASK_WAITING Tasks have been driven");

    transitionTaskStateQueue.queueSystemTask(() -> {
      attemptToRetry();
    });
    LOGGER.logInfo("All the previous CONTAINER_COMPLETED Tasks have been driven");
  }

  private void addContainerRequest(TaskStatus taskStatus) throws Exception {
    String taskRoleName = taskStatus.getTaskRoleName();
    TaskStatusLocator taskLocator = new TaskStatusLocator(taskRoleName, taskStatus.getTaskIndex());
    ContainerRequest request = setupContainerRequest(taskStatus);

    LOGGER.logInfo("%s: addContainerRequest: %s", taskLocator, HadoopExtensions.toString(request));
    rmClient.addContainerRequest(request);
    statusManager.transitionTaskState(taskLocator, TaskState.CONTAINER_REQUESTED);
  }

  private void addContainerRequest() throws Exception {
    for (TaskStatus taskStatus : statusManager.getTaskStatus(
        new HashSet<>(Collections.singletonList(TaskState.TASK_WAITING)))) {
      addContainerRequest(taskStatus);
    }
  }

  private void completeTask(TaskStatus taskStatus) throws Exception {
    String taskRoleName = taskStatus.getTaskRoleName();
    TaskStatusLocator taskLocator = new TaskStatusLocator(taskRoleName, taskStatus.getTaskIndex());

    LOGGER.logSplittedLines(Level.INFO,
        "%s: completeTask: TaskStatus:\n%s",
        taskLocator, WebCommon.toJson(taskStatus));

    statusManager.transitionTaskState(taskLocator, TaskState.TASK_COMPLETED);
    attemptToStop(taskStatus);
  }

  private void retryTask(TaskStatus taskStatus, RetryPolicyState newRetryPolicyState) throws Exception {
    String taskRoleName = taskStatus.getTaskRoleName();
    TaskStatusLocator taskLocator = new TaskStatusLocator(taskRoleName, taskStatus.getTaskIndex());

    LOGGER.logSplittedLines(Level.INFO,
        "%s: retryTask: NewRetryPolicyState:\n%s",
        taskLocator, WebCommon.toJson(newRetryPolicyState));

    statusManager.transitionTaskState(taskLocator, TaskState.TASK_WAITING, null, 0, null, newRetryPolicyState);
    addContainerRequest(taskStatus);
  }

  // Implement TaskRetryPolicy
  private void attemptToRetry(TaskStatus taskStatus) throws Exception {
    String taskRoleName = taskStatus.getTaskRoleName();
    TaskStatusLocator taskLocator = new TaskStatusLocator(taskRoleName, taskStatus.getTaskIndex());
    Integer exitCode = taskStatus.getContainerExitCode();
    ExitType exitType = taskStatus.getContainerExitType();
    Integer retriedCount = taskStatus.getTaskRetryPolicyState().getRetriedCount();
    RetryPolicyState newRetryPolicyState = YamlUtils.deepCopy(taskStatus.getTaskRetryPolicyState(), RetryPolicyState.class);
    String logPrefix = String.format("%s: attemptToRetry: ", taskLocator);

    LOGGER.logSplittedLines(Level.INFO,
        logPrefix + "ContainerExitCode: [%s], ContainerExitType: [%s], RetryPolicyState:\n[%s]",
        exitCode, exitType, WebCommon.toJson(newRetryPolicyState));

    // 1. ContainerSucceeded
    if (exitCode == ExitStatusKey.SUCCEEDED.toInt()) {
      LOGGER.logInfo(logPrefix +
          "Will completeTask with TaskSucceeded. Reason: " +
          "ContainerExitCode = %s.", exitCode);

      completeTask(taskStatus);
      return;
    }

    // 2. ContainerFailed
    Boolean generateContainerIpList = requestManager.getPlatParams().getGenerateContainerIpList();
    RetryPolicyDescriptor retryPolicy = requestManager.getTaskRetryPolicies().get(taskRoleName);
    String completeTaskLogPrefix = logPrefix + "Will completeTask with TaskFailed. Reason: ";
    String retryTaskLogPrefix = logPrefix + "Will retryTask with new Container. Reason: ";

    // 2.1. Handle Special Case
    if (generateContainerIpList) {
      LOGGER.logWarning(completeTaskLogPrefix +
          "TaskRetryPolicy is ignored due to GenerateContainerIpList enabled.");

      completeTask(taskStatus);
      return;
    }

    // 2.2. FancyRetryPolicy
    String fancyRetryPolicyLogSuffix = String.format("FancyRetryPolicy: %s Failure Occurred.", exitType);
    if (exitType == ExitType.NON_TRANSIENT) {
      newRetryPolicyState.setNonTransientRetriedCount(newRetryPolicyState.getNonTransientRetriedCount() + 1);
      if (retryPolicy.getFancyRetryPolicy()) {
        LOGGER.logWarning(completeTaskLogPrefix + fancyRetryPolicyLogSuffix);
        completeTask(taskStatus);
        return;
      }
    } else if (exitType == ExitType.TRANSIENT_NORMAL) {
      newRetryPolicyState.setTransientNormalRetriedCount(newRetryPolicyState.getTransientNormalRetriedCount() + 1);
      if (retryPolicy.getFancyRetryPolicy()) {
        LOGGER.logWarning(retryTaskLogPrefix + fancyRetryPolicyLogSuffix);
        retryTask(taskStatus, newRetryPolicyState);
        return;
      }
    } else if (exitType == ExitType.TRANSIENT_CONFLICT) {
      newRetryPolicyState.setTransientConflictRetriedCount(newRetryPolicyState.getTransientConflictRetriedCount() + 1);
      if (retryPolicy.getFancyRetryPolicy()) {
        LOGGER.logWarning(retryTaskLogPrefix + fancyRetryPolicyLogSuffix);
        retryTask(taskStatus, newRetryPolicyState);
        return;
      }
    } else {
      newRetryPolicyState.setUnKnownRetriedCount(newRetryPolicyState.getUnKnownRetriedCount() + 1);
      if (retryPolicy.getFancyRetryPolicy()) {
        // FancyRetryPolicy only handle Transient and NON_TRANSIENT Failure specially,
        // Leave UNKNOWN Failure to NormalRetryPolicy
        LOGGER.logWarning(logPrefix +
            "Transfer the RetryDecision to NormalRetryPolicy. Reason: " +
            fancyRetryPolicyLogSuffix);
      }
    }

    // 2.3. NormalRetryPolicy
    if (retryPolicy.getMaxRetryCount() != GlobalConstants.USING_UNLIMITED_VALUE &&
        retriedCount >= retryPolicy.getMaxRetryCount()) {
      LOGGER.logWarning(completeTaskLogPrefix +
              "RetriedCount %s has reached MaxRetryCount %s.",
          retriedCount, retryPolicy.getMaxRetryCount());
      completeTask(taskStatus);
      return;
    } else {
      newRetryPolicyState.setRetriedCount(newRetryPolicyState.getRetriedCount() + 1);

      LOGGER.logWarning(retryTaskLogPrefix +
              "RetriedCount %s has not reached MaxRetryCount %s.",
          retriedCount, retryPolicy.getMaxRetryCount());
      retryTask(taskStatus, newRetryPolicyState);
      return;
    }
  }

  private void attemptToRetry() throws Exception {
    for (TaskStatus taskStatus : statusManager.getTaskStatus(
        new HashSet<>(Collections.singletonList(TaskState.CONTAINER_COMPLETED)))) {
      attemptToRetry(taskStatus);
    }
  }

  private void completeContainer(String containerId, int exitCode, String diagnostics, Boolean needToRelease) throws Exception {
    if (needToRelease) {
      tryToReleaseContainer(containerId);
      if (exitCode == ExitStatusKey.CONTAINER_MIGRATE_TASK_REQUESTED.toInt()) {
        requestManager.onMigrateTaskRequestContainerReleased(containerId);
      }
    }

    String logSuffix = String.format(
        "[%s]: completeContainer: ExitCode: %s, Diagnostics: %s, NeedToRelease: %s",
        containerId, exitCode, diagnostics, needToRelease);

    if (!statusManager.isContainerIdLiveAssociated(containerId)) {
      LOGGER.logDebug("[NotLiveAssociated]%s", logSuffix);
      return;
    }

    TaskStatus taskStatus = statusManager.getTaskStatusWithLiveAssociatedContainerId(containerId);
    String taskRoleName = taskStatus.getTaskRoleName();
    TaskStatusLocator taskLocator = new TaskStatusLocator(taskRoleName, taskStatus.getTaskIndex());
    String linePrefix = String.format("%s: ", taskLocator);

    LOGGER.logSplittedLines(Level.INFO,
        "%s%s\n%s",
        taskLocator, logSuffix, generateContainerDiagnostics(taskStatus, linePrefix));

    statusManager.transitionTaskState(taskLocator, TaskState.CONTAINER_COMPLETED, null, exitCode, diagnostics);

    // Post-mortem CONTAINER_COMPLETED Task
    if (requestManager.getPlatParams().getAntiaffinityAllocation()) {
      aaAllocationManager.addCandidateRequestHostName(taskStatus.getContainerHost());
    }
    attemptToRetry(taskStatus);
  }

  private void completeContainers(List<String> containerIds, int exitCode, String diagnostics, Boolean needToRelease) throws Exception {
    for (String containerId : containerIds) {
      completeContainer(containerId, exitCode, diagnostics, needToRelease);
    }
  }

  private void completeContainers(List<ContainerStatus> containerStatuses) throws Exception {
    for (ContainerStatus containerStatus : containerStatuses) {
      completeContainer(
          containerStatus.getContainerId().toString(),
          containerStatus.getExitStatus(),
          containerStatus.getDiagnostics(),
          false);
    }
  }

  private Set<String> resyncTasksWithLiveContainers(HashSet<String> liveContainerIds) throws Exception {
    String logScope = "resyncTasksWithLiveContainers";
    Set<String> retainContainerIds = new HashSet<String>();

    if (liveContainerIds == null) {
      LOGGER.logInfo(
          "Got null live Containers from RM, so RMResync is incomplete. " +
              "resetContainerConnectionLostCount for all tasks, since around this time RMResync must also be incomplete.");
      statusManager.resetContainerConnectionLostCount();
    } else {
      CHANGE_AWARE_LOGGER.initializeScope(logScope, Level.INFO);
      CHANGE_AWARE_LOGGER.log(logScope,
          "Got %s live Containers from RM, start to resync them.",
          liveContainerIds.size());

      for (String containerId : liveContainerIds) {
        if (statusManager.isContainerIdLiveAssociated(containerId)) {
          statusManager.resetContainerConnectionLostCount(containerId);
          retainContainerIds.add(containerId);
        } else {
          if (!containerConnectionExceedCount.containsKey(containerId)) {
            containerConnectionExceedCount.put(containerId, 0);
          }
          containerConnectionExceedCount.put(containerId, containerConnectionExceedCount.get(containerId) + 1);
          Integer exceedCount = containerConnectionExceedCount.get(containerId);

          LOGGER.logWarning(
              "Cannot find resynced live Container %s in live associated Containers. " +
                  "IncreaseContainerConnectionExceedCount to %s.",
              containerId, exceedCount);

          Integer maxExceedCount = requestManager.getPlatParams().getContainerConnectionMaxExceedCount();
          if (exceedCount > maxExceedCount) {
            LOGGER.logWarning(
                "Live Container %s's ContainerConnectionExceedCount %s " +
                    "exceed ContainerConnectionMaxExceedCount %s. " +
                    "Will complete it with RMResyncExceed ExitStatus",
                containerId, exceedCount, maxExceedCount);

            // This may Release the Container which is Allocated in RM, but AM has not got notified
            // through the onContainersAllocated.
            // To avoid this, we need to ensure the Release happens after onContainersAllocated, i.e.
            // AMRMHeartbeatIntervalSec < ContainerConnectionMaxExceedCount * AMRMResyncIntervalSec
            completeContainer(
                containerId,
                ExitStatusKey.CONTAINER_RM_RESYNC_EXCEED.toInt(),
                "Container exceed after RMResynced",
                true);

            // Pending Exceed Container now is settled to definitely Exceed Container
            containerConnectionExceedCount.remove(containerId);
          } else {
            retainContainerIds.add(containerId);
          }
        }
      }

      List<String> liveAssociatedContainerIds = statusManager.getLiveAssociatedContainerIds();
      for (String containerId : liveAssociatedContainerIds) {
        if (!liveContainerIds.contains(containerId)) {
          TaskStatus taskStatus = statusManager.getTaskStatusWithLiveAssociatedContainerId(containerId);
          String taskRoleName = taskStatus.getTaskRoleName();
          TaskStatusLocator taskLocator = new TaskStatusLocator(taskRoleName, taskStatus.getTaskIndex());

          statusManager.increaseContainerConnectionLostCount(containerId);
          Integer lostCount = taskStatus.getContainerConnectionLostCount();
          LOGGER.logWarning(
              "%s: Cannot find live associated Container %s in resynced live Containers. " +
                  "increaseContainerConnectionLostCount to %s.",
              taskLocator, containerId, lostCount);

          Integer maxLostCount = requestManager.getPlatParams().getContainerConnectionMaxLostCount();
          if (maxLostCount == GlobalConstants.USING_DEFAULT_VALUE) {
            maxLostCount = conf.getLauncherConfig().getAmRmResyncFrequency();
          }

          // This may Mis-Complete the Container, when liveContainerIds is incomplete.
          // If miss judging rate is still too high, we need to combine the ContainerStatus from NM to
          // double confirm the Container is lost/complete.
          if (maxLostCount != GlobalConstants.USING_UNLIMITED_VALUE &&
              lostCount > maxLostCount) {
            LOGGER.logWarning(
                "%s: Live associated Container %s's ContainerConnectionLostCount %s " +
                    "exceed ContainerConnectionMaxLostCount %s. " +
                    "Will complete it with RMResyncLost ExitStatus",
                taskLocator, containerId, lostCount, maxLostCount);

            completeContainer(
                containerId,
                ExitStatusKey.CONTAINER_RM_RESYNC_LOST.toInt(),
                "Container lost after RMResynced",
                true);
          } else {
            retainContainerIds.add(containerId);
          }
        }
      }
    }

    return retainContainerIds;
  }

  private void allocateContainer(Container container) throws Exception {
    String containerId = container.getId().toString();
    String containerHostName = container.getNodeId().getHost();
    Boolean aaAllocation = requestManager.getPlatParams().getAntiaffinityAllocation();
    Boolean generateContainerIpList = requestManager.getPlatParams().getGenerateContainerIpList();

    LOGGER.logInfo(
        "[%s]: allocateContainer: Try to Allocate Container to Task: Container: %s",
        containerId, HadoopExtensions.toString(container));

    // 1. findTask
    TaskStatus taskStatus = findTask(container);
    if (taskStatus == null) {
      LOGGER.logDebug(
          "[%s]: Cannot find a suitable Task to accept the Allocate Container. It should be exceeded.",
          containerId);
      tryToReleaseContainer(containerId);
      return;
    }
    String taskRoleName = taskStatus.getTaskRoleName();
    TaskStatusLocator taskLocator = new TaskStatusLocator(taskRoleName, taskStatus.getTaskIndex());

    // 2. testContainer
    if (!testContainer(container)) {
      LOGGER.logInfo(
          "%s[%s]: Container is Rejected, Release Container and Request again",
          taskLocator, containerId);
      tryToReleaseContainer(containerId);
      addContainerRequest(taskStatus);
      return;
    }

    // 3. allocateContainer
    try {
      statusManager.transitionTaskState(taskLocator, TaskState.CONTAINER_ALLOCATED, container);
      if (aaAllocation) {
        aaAllocationManager.removeCandidateRequestHostName(containerHostName);
      }
      LOGGER.logInfo("%s[%s]: Succeeded to Allocate Container to Task", taskLocator, containerId);

      if (containerConnectionExceedCount.containsKey(containerId)) {
        // Pending Exceed Container now is settled to live associated Container
        containerConnectionExceedCount.remove(containerId);
      }
    } catch (Exception e) {
      LOGGER.logWarning(e,
          "%s[%s]: Failed to Allocate Container to Task, Release Container and Request again",
          taskLocator, containerId);
      tryToReleaseContainer(containerId);
      addContainerRequest(taskStatus);
      return;
    }

    // 4. launchContainer
    if (!generateContainerIpList) {
      launchContainer(taskStatus, container);
    } else {
      allocatedContainers.put(containerId, container);

      int neverBeenAllocatedTaskCount = statusManager.getTaskCount(
          new HashSet<>(Arrays.asList(TaskState.TASK_WAITING, TaskState.CONTAINER_REQUESTED)));
      if (neverBeenAllocatedTaskCount == 0) {
        launchContainersTogether();
      } else {
        LOGGER.logInfo(
            "Waiting for %s never been CONTAINER_ALLOCATED Tasks to become CONTAINER_ALLOCATED, " +
                "since GenerateContainerIpList enabled",
            neverBeenAllocatedTaskCount);
      }
    }
  }

  private void allocateContainers(List<Container> containers) throws Exception {
    LOGGER.logInfo(
        "allocateContainers: Try to Allocate %s Containers to Tasks",
        containers.size());

    for (Container container : containers) {
      allocateContainer(container);
    }
  }

  private void launchContainer(TaskStatus taskStatus, Container container) throws Exception {
    String taskRoleName = taskStatus.getTaskRoleName();
    TaskStatusLocator taskLocator = new TaskStatusLocator(taskRoleName, taskStatus.getTaskIndex());
    String containerId = container.getId().toString();
    assert containerId.equals(taskStatus.getContainerId());

    LOGGER.logInfo("%s[%s]: launchContainer", taskLocator, containerId);

    ContainerLaunchContext launchContext = setupContainerLaunchContext(taskStatus);
    nmClient.startContainerAsync(container, launchContext);
    statusManager.transitionTaskState(taskLocator, TaskState.CONTAINER_LAUNCHED);
  }

  private void launchContainersTogether() throws Exception {
    List<TaskStatus> taskStatuses = statusManager.getTaskStatus(
        new HashSet<>(Collections.singletonList(TaskState.CONTAINER_ALLOCATED)));
    Boolean generateContainerIpList = requestManager.getPlatParams().getGenerateContainerIpList();

    LOGGER.logInfo("launchContainersTogether: %s Tasks", taskStatuses.size());

    if (generateContainerIpList) {
      StringBuilder fileContent = new StringBuilder();
      for (TaskStatus taskStatus : taskStatuses) {
        fileContent.append(taskStatus.getContainerIp());
        fileContent.append("\n");
      }
      CommonUtils.writeFile(GlobalConstants.CONTAINER_IP_LIST_FILE, fileContent.toString());

      try {
        hdfsStore.uploadContainerIpListFile(conf.getFrameworkName());
        HadoopUtils.invalidateLocalResourcesCache();
      } catch (Exception e) {
        // It contains HDFS OP, so handle the corresponding Exception ASAP
        handleException(e);
      }
    }

    for (TaskStatus taskStatus : taskStatuses) {
      String containerId = taskStatus.getContainerId();
      assert allocatedContainers.containsKey(containerId);

      launchContainer(taskStatus, allocatedContainers.get(taskStatus.getContainerId()));
    }
  }

  private void onContainerStartSucceeded(String containerId) throws Exception {
    String logSuffix = String.format("[%s]: onContainerStartSucceeded", containerId);

    if (!statusManager.isContainerIdLiveAssociated(containerId)) {
      LOGGER.logWarning("[NotLiveAssociated]%s", logSuffix);
      return;
    }

    TaskStatus taskStatus = statusManager.getTaskStatusWithLiveAssociatedContainerId(containerId);
    String taskRoleName = taskStatus.getTaskRoleName();
    TaskStatusLocator taskLocator = new TaskStatusLocator(taskRoleName, taskStatus.getTaskIndex());
    String linePrefix = String.format("%s: ", taskLocator);

    LOGGER.logSplittedLines(Level.INFO,
        "%s%s\n%s",
        taskLocator, logSuffix, generateContainerDiagnostics(taskStatus, linePrefix));

    statusManager.transitionTaskState(taskLocator, TaskState.CONTAINER_RUNNING);
  }

  private void onContainerStartFailed(String containerId, Throwable e) throws Exception {
    String logSuffix = String.format(
        "[%s]: onContainerStartFailed.%s",
        containerId, CommonUtils.toString(e));

    if (!statusManager.isContainerIdLiveAssociated(containerId)) {
      LOGGER.logWarning("[NotLiveAssociated]%s", logSuffix);
      return;
    }

    TaskStatus taskStatus = statusManager.getTaskStatusWithLiveAssociatedContainerId(containerId);
    String taskRoleName = taskStatus.getTaskRoleName();
    TaskStatusLocator taskLocator = new TaskStatusLocator(taskRoleName, taskStatus.getTaskIndex());

    String diagnostics = String.format("%s%s", taskLocator, logSuffix);
    LOGGER.logInfo(diagnostics);
    completeContainer(
        containerId,
        ExitStatusKey.CONTAINER_START_FAILED.toInt(),
        diagnostics,
        true);
  }

  /**
   * REGION Callbacks
   */
  // AM integrate and process all Callbacks from all its SubServices
  // Note, if a Callback may change TaskState/TaskStatus, it should be queued in transitionTaskStateQueue
  // to let Callee(SystemTaskQueue) to handle it in order.
  // Note:
  //  1. Queued SystemTask need to double check whether the input param still valid at the time being Executed.
  //  2. For Status: Do not queue SystemTask with Status as the input param otherwise need to double check its
  //  validity inside the SystemTask.
  //  3. For Request: Always need to double check the corresponding Request's validity inside the SystemTask,
  //  since RequestManager is not synchronized.
  // For AM:
  //  1. For Status: Status is not queued.
  //  2. For Request: Since AM does not support change TaskRole on the fly, it does not need to double check the Request.

  // Callbacks from SubServices
  public void onExceptionOccurred(Exception e) {
    LOGGER.logInfo(e, "onExceptionOccurred");

    // Handle SubService Exception ASAP
    handleException(e);
  }

  // Callbacks from StatusManager and RequestManager
  public void onDefaultTaskNodeLabelUpdated(String taskNodeLabel) throws Exception {
    LOGGER.logInfo("onDefaultTaskNodeLabelUpdated: TaskNodeLabel: [%s]", taskNodeLabel);

    String effectiveTaskNodeLabel;
    if (taskNodeLabel == null) {
      LOGGER.logInfo(
          "TaskNodeLabel not specified, using AMQueueDefaultNodeLabel [%s] as EffectiveRequestNodeLabel",
          conf.getAmQueueDefaultNodeLabel());
      effectiveTaskNodeLabel = conf.getAmQueueDefaultNodeLabel();
    } else {
      effectiveTaskNodeLabel = taskNodeLabel;
    }

    List<String> accessibleHostNames = HadoopUtils.getCurrentAccessibleHostNames(yarnClient, effectiveTaskNodeLabel);
    transitionTaskStateQueue.queueSystemTask(() -> {
      updateAntiaffinityAllocation(accessibleHostNames);
    });
  }

  // TaskRoleName -> ServiceVersion
  // AM may need to double check whether ServiceVersions is changed or not according to StatusManager
  public void onServiceVersionsUpdated(Map<String, Integer> serviceVersions) {
    LOGGER.logInfo("onServiceVersionsUpdated: ServiceVersions: %s", CommonExtensions.toString(serviceVersions));

    // TODO: Implement Service Rolling Upgrade
    // Just invalidate old Service cache
    HadoopUtils.invalidateLocalResourcesCache();
  }

  // TaskRoleName -> TaskNumber
  // AM may need to double check whether TaskNumbers is changed or not according to StatusManager
  public void onTaskNumbersUpdated(Map<String, Integer> taskNumbers) {
    LOGGER.logInfo("onTaskNumbersUpdated: TaskNumbers: %s", CommonExtensions.toString(taskNumbers));

    // In case TaskNumbers Increased
    transitionTaskStateQueue.queueSystemTask(() -> {
      statusManager.updateTaskNumbers(taskNumbers);
      addContainerRequest();
    });
  }

  // Cleanup Task level external resource [RM] before RemoveTask by DecreaseTaskNumber
  public void onTaskToRemove(TaskStatus taskStatus) {
    String containerId = taskStatus.getContainerId();
    TaskState taskState = taskStatus.getTaskState();

    if (TaskStateDefinition.CONTAINER_LIVE_ASSOCIATED_STATES.contains(taskState)) {
      // No need to completeContainer, since it is to be Removed afterwards
      tryToReleaseContainer(containerId);
    }
  }

  public void onStartRMResyncHandler() {
    LOGGER.logInfo("onStartRMResyncHandler");
    rmResyncHandler.start();
    LOGGER.logInfo("All the previous CONTAINER_RUNNING Tasks have been driven");
  }

  public void onStartTransitionTaskStateQueue() {
    LOGGER.logInfo("onStartTransitionTaskStateQueue");
    transitionTaskStateQueue.start();
    LOGGER.logInfo("Running TransitionTaskStateQueue");
  }

  public void onMigrateTaskRequested(String containerId, MigrateTaskRequest migrateTaskRequest) throws IOException {
    LOGGER.logSplittedLines(Level.INFO,
        "OnMigrateTask: ContainerId: %s MigrateTaskRequest:\n%s",
        containerId, WebCommon.toJson(migrateTaskRequest));

    transitionTaskStateQueue.queueSystemTask(() -> {
      completeContainer(
          containerId,
          ExitStatusKey.CONTAINER_MIGRATE_TASK_REQUESTED.toInt(),
          "Container killed due to MigrateTaskRequest",
          true);
    });
  }


  // Callbacks from RMResyncHandler
  public void queueResyncWithRM(int delaySec) {
    transitionTaskStateQueue.queueSystemTaskDelayed(() -> {
      try {
        rmResyncHandler.resyncWithRM();
      } catch (Exception e) {
        handleException(e);
      }
    }, delaySec * 1000);
  }

  public void onLiveContainersUpdated(HashSet<String> liveContainerIds) throws Exception {
    // onLiveContainersUpdated is already in queue, so queue it again will disorder
    // the result of resyncWithRM and other SystemTasks
    resyncTasksWithLiveContainers(liveContainerIds);
  }


  // Callbacks from RMClient
  public void onError(Throwable e) {
    rmClientFailureCount++;

    int rmClientMaxFailureCount = GlobalConstants.USING_UNLIMITED_VALUE;
    if (conf.getLauncherConfig() != null) {
      rmClientMaxFailureCount = conf.getLauncherConfig().getAmRmClientMaxFailureCount();
    }

    LOGGER.logWarning(e,
        "[%s / %s]: onError called into AM from RM, maybe this AM failed to heartbeat with RM.",
        rmClientFailureCount, rmClientMaxFailureCount);

    if (rmClientMaxFailureCount != GlobalConstants.USING_UNLIMITED_VALUE &&
        rmClientFailureCount > rmClientMaxFailureCount) {
      // YarnException indicates exceptions from yarn servers, and IOException indicates exceptions from RPC layer.
      // So, consider YarnException as NonTransientError, and IOException as TransientError.
      if (e instanceof YarnException) {
        stopForInternalNonTransientError(String.format(
            "onError called into AM from RM due to non-transient error, maybe application is non-compliant.%s",
            CommonUtils.toString(e)));
      } else if (e instanceof IOException) {
        stopForInternalTransientError(String.format(
            "onError called into AM from RM due to transient error, maybe YARN RM is down.%s",
            CommonUtils.toString(e)));
      } else {
        stopForInternalUnKnownError(String.format(
            "onError called into AM from RM due to unknown error.%s",
            CommonUtils.toString(e)));
      }
    }
  }

  public void onShutdownRequest() {
    stopForInternalTransientError(
        "onShutdownRequest called into AM from RM, maybe this Attempt does not exist in RM.");
  }

  public float getProgress() throws Exception {
    // getProgress will be called only when there is still successful heartbeat.
    rmClientFailureCount = 0;

    // Note queueSystemTask and wait its result here will block the RMClient
    // Deliver ApplicationProgress to RM on next heartbeat
    float progress = getApplicationProgress();

    String logScope = "getApplicationProgress";
    CHANGE_AWARE_LOGGER.initializeScope(logScope, Level.DEBUG);
    CHANGE_AWARE_LOGGER.log(logScope,
        "getProgress called into AM from RM: Progress: [%s]", progress);

    return progress;
  }

  public void onNodesUpdated(List<NodeReport> nodeReports) {
    if (nodeReports.size() <= 0) {
      return;
    }
    LOGGER.logDebug("onNodesUpdated: nodeReports: %s", nodeReports.size());

    transitionTaskStateQueue.queueSystemTask(() -> {
      updateNodeReport(nodeReports);
    });

    // TODO: Update TaskStatus.ContainerIsDecommissioning
  }

  public void onContainersAllocated(List<Container> allocatedContainers) {
    if (allocatedContainers.size() <= 0) {
      return;
    }

    LOGGER.logInfo(
        "onContainersAllocated: Allocated Containers: %s. " +
            "Will removeContainerRequest for them.",
        allocatedContainers.size());

    // RemoveContainerRequest ASAP, No need to queueSystemTask for it.
    // When the AM receives a new Container, we need to inform the RMClient,
    // which keeps track of all Requests, to decrease the number of them.
    for (Container allocatedContainer : allocatedContainers) {
      String containerId = allocatedContainer.getId().toString();
      try {
        rmClient.removeContainerRequest(setupContainerRequest(allocatedContainer));
      } catch (Exception e) {
        LOGGER.logError(e,
            "Failed to removeContainerRequest for Allocated Container %s",
            containerId);
      }
    }

    transitionTaskStateQueue.queueSystemTask(() -> {
      allocateContainers(allocatedContainers);
    });
  }

  public void onContainersCompleted(List<ContainerStatus> completedContainers) {
    if (completedContainers.size() <= 0) {
      return;
    }

    LOGGER.logInfo(
        "onContainersCompleted: Completed Containers: %s.",
        completedContainers.size());

    transitionTaskStateQueue.queueSystemTask(() -> {
      completeContainers(completedContainers);
    });
  }

  public void onPreemptionMessage(PreemptionMessage message) {
    //TODO: Do some work to save current work, otherwise, the container will be released by RM.
    //By default, no action take is ok.
  }

  // Callbacks from NMClient
  public void onContainerStarted(ContainerId containerId, Map<String, ByteBuffer> allServiceResponse) {
    transitionTaskStateQueue.queueSystemTask(() -> {
      onContainerStartSucceeded(containerId.toString());
    });
  }

  public void onStartContainerError(ContainerId containerId, Throwable e) {
    transitionTaskStateQueue.queueSystemTask(() -> {
      onContainerStartFailed(containerId.toString(), e);
    });
  }

  // Since we should only use nmClient to start container, below Callbacks will never occur
  public void onContainerStopped(ContainerId containerId) {
  }

  public void onStopContainerError(ContainerId containerId, Throwable e) {
  }

  public void onContainerStatusReceived(ContainerId containerId, ContainerStatus containerStatus) {
  }

  public void onGetContainerStatusError(ContainerId containerId, Throwable e) {
  }

  /**
   * REGION ReadInterface
   */
  public List<String> getLiveAssociatedHostNames() {
    List<String> hostNames = new ArrayList<>();
    hostNames.add(conf.getAmHostName());
    hostNames.addAll(statusManager.getLiveAssociatedHostNames());
    return hostNames;
  }

  public Integer getServiceVersion(String taskRoleName) {
    return requestManager.getServiceVersion(taskRoleName);
  }

  public boolean existsLocalVersionFrameworkRequest() throws NotAvailableException {
    if (requestManager == null) {
      throw new NotAvailableException("FrameworkRequest for local FrameworkVersion is not available");
    } else {
      return requestManager.existsLocalVersionFrameworkRequest();
    }
  }

  protected ClusterConfiguration getClusterConfiguration() {
    return requestManager.getClusterConfiguration();
  }
}