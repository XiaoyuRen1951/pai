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

package com.microsoft.frameworklauncher.common;

import org.apache.commons.lang.StringUtils;

// Define Launcher WebStructure
public class WebStructure {
  private static final String PATH_SEPARATOR = "/";

  public static final String FRAMEWORK_NAME_PATH_PARAM = "FrameworkName";
  public static final String TASK_ROLE_NAME_PATH_PARAM = "TaskRoleName";
  public static final String CONTAINER_ID_PATH_PARAM = "ContainerId";

  private static final String FRAMEWORK_NAME_PATH_PARAM_PLACEMENT = "{" + FRAMEWORK_NAME_PATH_PARAM + "}";
  private static final String TASK_ROLE_NAME_PATH_PARAM_PLACEMENT = "{" + TASK_ROLE_NAME_PATH_PARAM + "}";
  private static final String CONTAINER_ID_PATH_PARAM_PLACEMENT = "{" + CONTAINER_ID_PATH_PARAM + "}";

  public static final String ROOT_PATH = "/v1";
  public static final String LAUNCHER_STATUS_PATH = ROOT_PATH + PATH_SEPARATOR + "LauncherStatus";
  public static final String LAUNCHER_REQUEST_PATH = ROOT_PATH + PATH_SEPARATOR + "LauncherRequest";
  public static final String DATA_DEPLOYMENT_VERSION_PATH = LAUNCHER_REQUEST_PATH + PATH_SEPARATOR + "DataDeploymentVersion";
  public static final String FRAMEWORK_ROOT_PATH = ROOT_PATH + PATH_SEPARATOR + "Frameworks";
  public static final String FRAMEWORK_PATH = FRAMEWORK_ROOT_PATH + PATH_SEPARATOR + FRAMEWORK_NAME_PATH_PARAM_PLACEMENT;
  public static final String AGGREGATED_FRAMEWORK_STATUS_PATH = FRAMEWORK_PATH + PATH_SEPARATOR + "AggregatedFrameworkStatus";
  public static final String FRAMEWORK_STATUS_PATH = FRAMEWORK_PATH + PATH_SEPARATOR + "FrameworkStatus";
  public static final String TASK_ROLE_PATH = FRAMEWORK_PATH + PATH_SEPARATOR + "TaskRoles" + PATH_SEPARATOR + TASK_ROLE_NAME_PATH_PARAM_PLACEMENT;
  public static final String TASK_ROLE_STATUS_PATH = TASK_ROLE_PATH + PATH_SEPARATOR + "TaskRoleStatus";
  public static final String TASK_STATUSES_PATH = TASK_ROLE_PATH + PATH_SEPARATOR + "TaskStatuses";
  public static final String TASK_NUMBER_PATH = TASK_ROLE_PATH + PATH_SEPARATOR + "TaskNumber";
  public static final String MIGRATE_TASK_PATH = FRAMEWORK_PATH + PATH_SEPARATOR + "MigrateTasks" + PATH_SEPARATOR + CONTAINER_ID_PATH_PARAM_PLACEMENT;
  public static final String APPLICATION_PROGRESS_PATH = FRAMEWORK_PATH + PATH_SEPARATOR + "ApplicationProgress";
  public static final String AGGREGATED_FRAMEWORK_REQUEST_PATH = FRAMEWORK_PATH + PATH_SEPARATOR + "AggregatedFrameworkRequest";
  public static final String FRAMEWORK_REQUEST_PATH = FRAMEWORK_PATH + PATH_SEPARATOR + "FrameworkRequest";

  public static String getNodePath(String parentNodePath, String nodeName) {
    return (StringUtils.stripEnd(parentNodePath, PATH_SEPARATOR) +
        PATH_SEPARATOR +
        StringUtils.stripStart(nodeName, PATH_SEPARATOR));
  }

  public static String getFrameworkPath(String frameworkName) {
    return FRAMEWORK_PATH
        .replace(FRAMEWORK_NAME_PATH_PARAM_PLACEMENT, frameworkName);
  }

  public static String getAggregatedFrameworkStatusPath(String frameworkName) {
    return AGGREGATED_FRAMEWORK_STATUS_PATH
        .replace(FRAMEWORK_NAME_PATH_PARAM_PLACEMENT, frameworkName);
  }

  public static String getFrameworkStatusPath(String frameworkName) {
    return FRAMEWORK_STATUS_PATH
        .replace(FRAMEWORK_NAME_PATH_PARAM_PLACEMENT, frameworkName);
  }

  private static String getTaskRolePath(String frameworkName, String taskRoleName) {
    return TASK_ROLE_PATH
        .replace(FRAMEWORK_NAME_PATH_PARAM_PLACEMENT, frameworkName)
        .replace(TASK_ROLE_NAME_PATH_PARAM_PLACEMENT, taskRoleName);
  }

  public static String getTaskRoleStatusPath(String frameworkName, String taskRoleName) {
    return TASK_ROLE_STATUS_PATH
        .replace(FRAMEWORK_NAME_PATH_PARAM_PLACEMENT, frameworkName)
        .replace(TASK_ROLE_NAME_PATH_PARAM_PLACEMENT, taskRoleName);
  }

  public static String getTaskStatusesPath(String frameworkName, String taskRoleName) {
    return TASK_STATUSES_PATH
        .replace(FRAMEWORK_NAME_PATH_PARAM_PLACEMENT, frameworkName)
        .replace(TASK_ROLE_NAME_PATH_PARAM_PLACEMENT, taskRoleName);
  }

  public static String getTaskNumberPath(String frameworkName, String taskRoleName) {
    return TASK_NUMBER_PATH
        .replace(FRAMEWORK_NAME_PATH_PARAM_PLACEMENT, frameworkName)
        .replace(TASK_ROLE_NAME_PATH_PARAM_PLACEMENT, taskRoleName);
  }

  public static String getMigrateTaskPath(String frameworkName, String containerId) {
    return MIGRATE_TASK_PATH
        .replace(FRAMEWORK_NAME_PATH_PARAM_PLACEMENT, frameworkName)
        .replace(CONTAINER_ID_PATH_PARAM_PLACEMENT, containerId);
  }

  public static String getApplicationProgressPath(String frameworkName) {
    return APPLICATION_PROGRESS_PATH
        .replace(FRAMEWORK_NAME_PATH_PARAM_PLACEMENT, frameworkName);
  }

  public static String getAggregatedFrameworkRequestPath(String frameworkName) {
    return AGGREGATED_FRAMEWORK_REQUEST_PATH
        .replace(FRAMEWORK_NAME_PATH_PARAM_PLACEMENT, frameworkName);
  }

  public static String getFrameworkRequestPath(String frameworkName) {
    return FRAMEWORK_REQUEST_PATH
        .replace(FRAMEWORK_NAME_PATH_PARAM_PLACEMENT, frameworkName);
  }
}