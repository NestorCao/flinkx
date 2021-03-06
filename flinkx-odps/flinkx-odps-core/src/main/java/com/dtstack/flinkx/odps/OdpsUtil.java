/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.flinkx.odps;

import com.aliyun.odps.Instance;
import com.aliyun.odps.Odps;
import com.aliyun.odps.Table;
import com.aliyun.odps.TableSchema;
import com.aliyun.odps.PartitionSpec;
import com.aliyun.odps.Partition;
import com.aliyun.odps.account.Account;
import com.aliyun.odps.account.AliyunAccount;
import com.aliyun.odps.data.RecordReader;
import com.aliyun.odps.task.SQLTask;
import com.aliyun.odps.tunnel.TableTunnel;
import com.dtstack.flinkx.util.RangeSplitUtil;
import com.dtstack.flinkx.util.RetryUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import static com.dtstack.flinkx.odps.OdpsConfigConstants.DEFAULT_ACCOUNT_TYPE;
import static com.dtstack.flinkx.odps.OdpsConfigConstants.DEFAULT_ODPS_SERVER;
import static com.dtstack.flinkx.odps.OdpsConfigConstants.PACKAGE_AUTHORIZED_PROJECT;

/**
 * Odps Utilities used for OdpsWriter and OdpsReader
 *
 * Company: www.dtstack.com
 * @author huyifan.zju@163.com
 */
public class OdpsUtil {
    private static final Logger LOG = LoggerFactory.getLogger(OdpsUtil.class);

    public static int MAX_RETRY_TIME = 3;

    public static final long BUFFER_SIZE_DEFAULT = 64 * 1024 * 1024;

    public static Odps initOdps(Map<String,String> odpsConfig) {
        String odpsServer = odpsConfig.get(OdpsConfigKeys.KEY_ODPS_SERVER);
        if(StringUtils.isBlank(odpsServer)) {
            odpsServer = DEFAULT_ODPS_SERVER;
        }

        String accessId = odpsConfig.get(OdpsConfigKeys.KEY_ACCESS_ID);
        if(StringUtils.isBlank(accessId)) {
            throw new IllegalArgumentException("accessId is required");
        }

        String accessKey = odpsConfig.get(OdpsConfigKeys.KEY_ACCESS_KEY);
        if(StringUtils.isBlank(accessKey)) {
            throw new IllegalArgumentException("accessKey is required");
        }

        String project = odpsConfig.get(OdpsConfigKeys.KEY_PROJECT);
        if(StringUtils.isBlank(project)) {
            throw new IllegalArgumentException("project is required");
        }

        String packageAuthorizedProject = odpsConfig.get(PACKAGE_AUTHORIZED_PROJECT);

        String defaultProject;
        if(StringUtils.isBlank(packageAuthorizedProject)) {
            defaultProject = project;
        } else {
            defaultProject = packageAuthorizedProject;
        }

        String accountType = odpsConfig.get(OdpsConfigKeys.KEY_ACCOUNT_TYPE);
        if(StringUtils.isBlank(accountType)) {
            accountType = DEFAULT_ACCOUNT_TYPE;
        }

        Account account = null;
        if (accountType.equalsIgnoreCase(DEFAULT_ACCOUNT_TYPE)) {
            account = new AliyunAccount(accessId, accessKey);
        } else {
            throw new IllegalArgumentException("Unsupported account type: " + accountType);
        }

        Odps odps = new Odps(account);
        odps.getRestClient().setConnectTimeout(10);
        odps.getRestClient().setReadTimeout(60);
        odps.getRestClient().setRetryTimes(2);
        odps.setDefaultProject(defaultProject);
        odps.setEndpoint(odpsServer);

        return odps;
    }

    public static boolean isPartitionedTable(Table table) {
        return getPartitionDepth(table) > 0;
    }

    public static int getPartitionDepth(Table table) {
        TableSchema tableSchema = table.getSchema();
        return tableSchema.getPartitionColumns().size();
    }

    public static List<String> getTableAllPartitions(Table table) {
        List<Partition> tableAllPartitions = table.getPartitions();

        List<String> retPartitions = new ArrayList<String>();

        if (null != tableAllPartitions) {
            for (Partition partition : tableAllPartitions) {
                retPartitions.add(partition.getPartitionSpec().toString());
            }
        }

        return retPartitions;
    }


    public static TableTunnel.DownloadSession createMasterSessionForNonPartitionedTable(Odps odps, String tunnelServer,
                                                                                        final String projectName, final String tableName) {
        final TableTunnel tunnel = new TableTunnel(odps);

        if (StringUtils.isNotEmpty(tunnelServer)) {
            tunnel.setEndpoint(tunnelServer);
        }

        try {
            return RetryUtil.executeWithRetry(new Callable<TableTunnel.DownloadSession>() {
                @Override
                public TableTunnel.DownloadSession call() throws Exception {
                    return tunnel.createDownloadSession(
                            projectName, tableName);
                }
            }, MAX_RETRY_TIME, 1000, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    public static TableTunnel.DownloadSession createMasterSessionForPartitionedTable(Odps odps, String tunnelServer,
                                                                                     final String projectName, final String tableName, String partition) {

        final TableTunnel tunnel = new TableTunnel(odps);
        if (StringUtils.isNotEmpty(tunnelServer)) {
            tunnel.setEndpoint(tunnelServer);
        }

        final PartitionSpec partitionSpec = new PartitionSpec(partition);

        try {
            return RetryUtil.executeWithRetry(new Callable<TableTunnel.DownloadSession>() {
                @Override
                public TableTunnel.DownloadSession call() throws Exception {
                    return tunnel.createDownloadSession(
                            projectName, tableName, partitionSpec);
                }
            }, MAX_RETRY_TIME, 1000, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }



    public static TableTunnel.DownloadSession getSlaveSessionForNonPartitionedTable(Odps odps, final String sessionId,
                                                                                    String tunnelServer, final String projectName, final String tableName) {
        final TableTunnel tunnel = new TableTunnel(odps);
        if (StringUtils.isNotEmpty(tunnelServer)) {
            tunnel.setEndpoint(tunnelServer);
        }

        try {
            return RetryUtil.executeWithRetry(new Callable<TableTunnel.DownloadSession>() {
                @Override
                public TableTunnel.DownloadSession call() throws Exception {
                    return tunnel.getDownloadSession(
                            projectName, tableName, sessionId);
                }
            }, MAX_RETRY_TIME ,1000, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static TableTunnel.DownloadSession getSlaveSessionForPartitionedTable(Odps odps, final String sessionId,
                                                                                 String tunnelServer, final String projectName, final String tableName, String partition) {
        final TableTunnel tunnel = new TableTunnel(odps);
        if (StringUtils.isNotEmpty(tunnelServer)) {
            tunnel.setEndpoint(tunnelServer);
        }

        final PartitionSpec partitionSpec = new PartitionSpec(partition);
        try {
            return RetryUtil.executeWithRetry(new Callable<TableTunnel.DownloadSession>() {
                @Override
                public TableTunnel.DownloadSession call() throws Exception {
                    return tunnel.getDownloadSession(
                            projectName, tableName, partitionSpec, sessionId);
                }
            }, MAX_RETRY_TIME, 1000, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }



    public static RecordReader getRecordReader(final TableTunnel.DownloadSession downloadSession, final long start, final long count,
                                               final boolean isCompress) {
        try {
            return downloadSession.openRecordReader(start, count, isCompress);
        } catch (Exception e) {
            throw new RuntimeException("Fail to open RecordRecord.", e);
        }

    }

    /**
     * Pair left: startIndex, right: stepCount
     */
    public static List<Pair<Long, Long>> splitRecordCount(long recordCount, int adviceNum) {
        if(recordCount<0){
            throw new IllegalArgumentException("????????? recordCount ???????????????.recordCount=" + recordCount);
        }

        if(adviceNum<1){
            throw new IllegalArgumentException("????????? adviceNum ???????????????.adviceNum=" + adviceNum);
        }

        List<Pair<Long, Long>> result = new ArrayList<Pair<Long, Long>>();
        // ???????????? RangeSplitUtil ??????????????????????????????0????????????
        if (recordCount == 0) {
            result.add(ImmutablePair.of(0L, 0L));
            return result;
        }

        long[] tempResult = RangeSplitUtil.doLongSplit(0L, recordCount - 1, adviceNum);

        tempResult[tempResult.length - 1]++;

        for (int i = 0; i < tempResult.length - 1; i++) {
            result.add(ImmutablePair.of(tempResult[i], (tempResult[i + 1] - tempResult[0])));
        }
        return result;
    }

    public static void checkTable(Odps odps, Table table, String partition, boolean truncate) {
        boolean isPartitionedTable = OdpsUtil.isPartitionedTable(table);

        if (truncate) {
            //?????? truncate
            if (isPartitionedTable) {
                //?????????
                if (StringUtils.isBlank(partition)) {
                    throw new RuntimeException(String.format("???????????????????????????????????????????????????????????????:%s ?????????????????? truncate ????????????????????????????????????????????????. ???????????????????????????????????? pt=${bizdate} .",
                            table.getName()));
                } else {
                    LOG.info("Try to truncate partition=[{}] in table=[{}].", partition, table.getName());
                    OdpsUtil.truncatePartition(odps, table, partition);
                }
            } else {
                //????????????
                if (StringUtils.isNotBlank(partition)) {
                    throw new RuntimeException(String.format("?????????????????????????????????ODPS??????????????????:%s ?????? truncate ???????????????????????????????????????. ??????????????????????????????????????????????????????.",
                            table.getName()));
                } else {
                    LOG.info("Try to truncate table:[{}].", table.getName());
                    OdpsUtil.truncateNonPartitionedTable(odps, table);
                }
            }
        } else {
            //????????? truncate
            if (isPartitionedTable) {
                //?????????
                if (StringUtils.isBlank(partition)) {
                    throw new RuntimeException(String.format("?????????????????????????????????????????????:%s ??????????????????????????????. ???????????????????????????????????????????????? ???????????? pt=${bizdate}.", table.getName()));
                } else {
                    boolean isPartitionExists = OdpsUtil.isPartitionExist(table, partition);
                    if (!isPartitionExists) {
                        LOG.info("Try to add partition:[{}] in table:[{}].", partition,
                                table.getName());
                        OdpsUtil.addPart(odps, table, partition);
                    }
                }
            } else {
                //????????????
                if (StringUtils.isNotBlank(partition)) {
                    throw new RuntimeException(String.format("???????????????????????????????????????????????????:%s ?????????????????????????????????. ???????????????????????????", table.getName()));
                }
            }
        }
    }

    public static void truncatePartition(Odps odps, Table table, String partition) {
        if (isPartitionExist(table, partition)) {
            dropPart(odps, table, partition);
        }
        addPart(odps, table, partition);
    }

    private static boolean isPartitionExist(Table table, String partition) {
        // check if exist partition ??????????????? null
        List<String> odpsParts = OdpsUtil.listOdpsPartitions(table);

        int j = 0;
        for (; j < odpsParts.size(); j++) {
            if (odpsParts.get(j).equals(partition)) {
                break;
            }
        }

        return j != odpsParts.size();
    }

    public static List<String> listOdpsPartitions(Table table) {
        List<String> parts = new ArrayList<String>();
        try {
            List<Partition> partitions = table.getPartitions();
            for(Partition partition : partitions) {
                parts.add(partition.getPartitionSpec().toString());
            }
        } catch (Exception e) {
            throw new RuntimeException(String.format("?????? ODPS ?????????:%s ?????????????????????. ????????? ODPS ???????????????.",
                    table.getName()), e);
        }
        return parts;
    }

    public static void addPart(Odps odps, Table table, String partition) {
        String partSpec = getPartSpec(partition);
        // add if not exists partition
        StringBuilder addPart = new StringBuilder();
        addPart.append("alter table ").append(table.getName()).append(" add IF NOT EXISTS partition(")
                .append(partSpec).append(");");
        try {
            runSqlTask(odps, addPart.toString());
        } catch (Exception e) {
            throw new RuntimeException(String.format("?????? ODPS ????????????????????????. ????????????????????? ODPS ?????????:%s ??????:%s ?????????:%s. ????????? ODPS ???????????????.",
                      table.getProject(), table.getName(), partition), e);
        }
    }

    private static void dropPart(Odps odps, Table table, String partition) {
        String partSpec = getPartSpec(partition);
        StringBuilder dropPart = new StringBuilder();
        dropPart.append("alter table ").append(table.getName())
                .append(" drop IF EXISTS partition(").append(partSpec)
                .append(");");
        try {
            runSqlTask(odps, dropPart.toString());
        } catch (Exception e) {
            throw new RuntimeException(String.format("Drop  ODPS ?????????????????????. ?????????????????????:%s ??????:%s ?????????:%s .????????? ODPS ???????????????.",
                    table.getProject(), table.getName(), partition), e);
        }
    }

    public static void runSqlTask(Odps odps, String query) {
        if (StringUtils.isBlank(query)) {
            return;
        }

        String taskName = "datax_odpswriter_trunacte_" + UUID.randomUUID().toString().replace('-', '_');

        LOG.info("Try to start sqlTask:[{}] to run odps sql:[\n{}\n] .", taskName, query);

        //todo:biz_id set (??????ddl?????????)
        Instance instance;
        Instance.TaskStatus status;
        String taskResult;
        try {
            instance = SQLTask.run(odps, odps.getDefaultProject(), query, taskName, null, null);
            instance.waitForSuccess();
            status = instance.getTaskStatus().get(taskName);
            taskResult = instance.getTaskResults().get(taskName);
        } catch(Exception e) {
            throw new RuntimeException(e);
        }

        if (!Instance.TaskStatus.Status.SUCCESS.equals(status.getStatus())) {
            throw new RuntimeException(String.format("ODPS ?????????????????? ODPS SQL??????, ????????????:%s. ????????? ODPS ???????????????. SQL ?????????:[\n%s\n].", taskResult, query));
        }
    }


    private static String getPartSpec(String partition) {
        StringBuilder partSpec = new StringBuilder();
        String[] parts = partition.split(",");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            String[] kv = part.split("=");
            if (kv.length != 2) {
                throw new RuntimeException(String.format("ODPS ?????????????????? partition:%s ????????????. ?????????????????????: pt=1,ds=hangzhou", partition));
            }
            partSpec.append(kv[0]).append("=");
            partSpec.append("'").append(kv[1].replace("'", "")).append("'");
            if (i != parts.length - 1) {
                partSpec.append(",");
            }
        }
        return partSpec.toString();
    }

    public static void truncateNonPartitionedTable(Odps odps, Table tab) {
        String truncateNonPartitionedTableSql = "truncate table " + tab.getName() + ";";

        try {
            runSqlTask(odps, truncateNonPartitionedTableSql);
        } catch (Exception e) {
            throw new RuntimeException(String.format(" ?????? ODPS ?????????:%s ??????, ????????? ODPS ???????????????.", tab.getName()), e);
        }
    }

    public static Table getTable(Odps odps, String projectName, String tableName) {
        return odps.tables().get(projectName, tableName);
    }

    public static TableTunnel.UploadSession createMasterTunnelUpload(final TableTunnel tunnel, final String projectName,
                                                                     final String tableName, final String partition) {
        if(StringUtils.isBlank(partition)) {
            try {
                return RetryUtil.executeWithRetry(new Callable<TableTunnel.UploadSession>() {
                    @Override
                    public TableTunnel.UploadSession call() throws Exception {
                        return tunnel.createUploadSession(projectName, tableName);
                    }
                }, MAX_RETRY_TIME, 1000L, true);
            } catch (Exception e) {
                throw new RuntimeException("Failed to construct TunnelUpload", e);
            }
        } else {
            final PartitionSpec partitionSpec = new PartitionSpec(partition);
            try {
                return RetryUtil.executeWithRetry(new Callable<TableTunnel.UploadSession>() {
                    @Override
                    public TableTunnel.UploadSession call() throws Exception {
                        return tunnel.createUploadSession(projectName, tableName, partitionSpec);
                    }
                }, MAX_RETRY_TIME, 1000L, true);
            } catch (Exception e) {
                throw new RuntimeException("Failed to construct TunnelUpload", e);
            }
        }
    }

}
