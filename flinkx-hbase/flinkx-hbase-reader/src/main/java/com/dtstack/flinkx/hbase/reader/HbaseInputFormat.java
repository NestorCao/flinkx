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

package com.dtstack.flinkx.hbase.reader;

import com.dtstack.flinkx.authenticate.KerberosUtil;
import com.dtstack.flinkx.hbase.HbaseHelper;
import com.dtstack.flinkx.inputformat.RichInputFormat;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.flink.api.common.io.DefaultInputSplitAssigner;
import org.apache.flink.api.common.io.statistics.BaseStatistics;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.io.InputSplit;
import org.apache.flink.core.io.InputSplitAssigner;
import org.apache.flink.types.Row;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.flink.hadoop.shaded.com.google.common.collect.Maps;


/**
 * The InputFormat Implementation used for HbaseReader
 *
 * Company: www.dtstack.com
 * @author huyifan.zju@163.com
 */
public class HbaseInputFormat extends RichInputFormat {

    protected Map<String,Object> hbaseConfig;
    protected String tableName;
    protected String startRowkey;
    protected String endRowkey;
    protected List<String> columnNames;
    protected List<String> columnValues;
    protected List<String> columnFormats;
    protected List<String> columnTypes;
    protected boolean isBinaryRowkey;
    protected String encoding;
    protected int scanCacheSize;
    protected int scanBatchSize;
    private transient Connection connection;
    private transient Scan scan;
    private transient Table table;
    private transient ResultScanner resultScanner;
    private transient Result next;
    private transient Map<String,byte[][]> nameMaps;

    private boolean openKerberos = false;

    @Override
    public void configure(Configuration configuration) {
        LOG.info("HbaseOutputFormat configure start");
        nameMaps = Maps.newConcurrentMap();

        connection = HbaseHelper.getHbaseConnection(hbaseConfig, jobId, "reader");

        LOG.info("HbaseOutputFormat configure end");
    }

    @Override
    public BaseStatistics getStatistics(BaseStatistics baseStatistics) throws IOException {
        return null;
    }

    @Override
    public InputSplit[] createInputSplits(int minNumSplits) throws IOException {
        return split(connection, tableName, startRowkey, endRowkey, isBinaryRowkey);
    }

    public HbaseInputSplit[] split(Connection hConn, String tableName, String startKey, String endKey, boolean isBinaryRowkey) {
        byte[] startRowkeyByte = HbaseHelper.convertRowkey(startKey, isBinaryRowkey);
        byte[] endRowkeyByte = HbaseHelper.convertRowkey(endKey, isBinaryRowkey);

        /* ????????????????????? startRowkey ??? endRowkey??????????????????startRowkey <= endRowkey */
        if (startRowkeyByte.length != 0 && endRowkeyByte.length != 0
                && Bytes.compareTo(startRowkeyByte, endRowkeyByte) > 0) {
            throw new IllegalArgumentException("startRowKey can't be bigger than endRowkey");
        }

        RegionLocator regionLocator = HbaseHelper.getRegionLocator(hConn, tableName);
        List<HbaseInputSplit> resultSplits;
        try {
            Pair<byte[][], byte[][]> regionRanges = regionLocator.getStartEndKeys();
            if (null == regionRanges) {
                throw new RuntimeException("Failed to retrieve rowkey ragne");
            }
            resultSplits = doSplit(startRowkeyByte, endRowkeyByte, regionRanges);

            LOG.info("HBaseReader split job into {} tasks.", resultSplits.size());
            return resultSplits.toArray(new HbaseInputSplit[resultSplits.size()]);
        } catch (Exception e) {
            throw new RuntimeException("Failed to split hbase table");
        }finally {
            HbaseHelper.closeRegionLocator(regionLocator);
        }
    }

    private List<HbaseInputSplit> doSplit(byte[] startRowkeyByte,
                                                 byte[] endRowkeyByte, Pair<byte[][], byte[][]> regionRanges) {

        List<HbaseInputSplit> configurations = new ArrayList<>();

        for (int i = 0; i < regionRanges.getFirst().length; i++) {

            byte[] regionStartKey = regionRanges.getFirst()[i];
            byte[] regionEndKey = regionRanges.getSecond()[i];

            // ?????????region???????????????region
            // ??????????????????region???start Key?????????????????????userEndKey,???????????????region????????????????????????
            // ????????????????????????userEndKey???"",??????????????????????????????userEndKey???""?????????????????????region
            if (Bytes.compareTo(regionEndKey, HConstants.EMPTY_BYTE_ARRAY) == 0
                    && (endRowkeyByte.length != 0 && (Bytes.compareTo(
                    regionStartKey, endRowkeyByte) > 0))) {
                continue;
            }

            // ???????????????region??????????????????region???
            // ???????????????userStartKey????????????region???endkey,?????????region??????????????????
            if ((Bytes.compareTo(regionEndKey, HConstants.EMPTY_BYTE_ARRAY) != 0)
                    && (Bytes.compareTo(startRowkeyByte, regionEndKey) >= 0)) {
                continue;
            }

            // ?????????????????????userEndKey???????????? region???startkey,?????????region??????????????????
            // ???????????????????????????userEndKey???"",??????????????????????????????userEndKey???""?????????????????????region
            if (endRowkeyByte.length != 0
                    && (Bytes.compareTo(endRowkeyByte, regionStartKey) <= 0)) {
                continue;
            }

            String thisStartKey = getStartKey(startRowkeyByte, regionStartKey);
            String thisEndKey = getEndKey(endRowkeyByte, regionEndKey);
            HbaseInputSplit hbaseInputSplit = new HbaseInputSplit(thisStartKey, thisEndKey);
            configurations.add(hbaseInputSplit);
        }

        return configurations;
    }

    private String getEndKey(byte[] endRowkeyByte, byte[] regionEndKey) {
        if (endRowkeyByte == null) {// ???????????????????????????????????????userStartKey????????????null
            throw new IllegalArgumentException("userEndKey should not be null!");
        }

        byte[] tempEndRowkeyByte;

        if (endRowkeyByte.length == 0) {
            tempEndRowkeyByte = regionEndKey;
        } else if (Bytes.compareTo(regionEndKey, HConstants.EMPTY_BYTE_ARRAY) == 0) {
            // ???????????????region
            tempEndRowkeyByte = endRowkeyByte;
        } else {
            if (Bytes.compareTo(endRowkeyByte, regionEndKey) > 0) {
                tempEndRowkeyByte = regionEndKey;
            } else {
                tempEndRowkeyByte = endRowkeyByte;
            }
        }

        return Bytes.toStringBinary(tempEndRowkeyByte);
    }

    private String getStartKey(byte[] startRowkeyByte, byte[] regionStarKey) {
        if (startRowkeyByte == null) {// ???????????????????????????????????????userStartKey????????????null
            throw new IllegalArgumentException(
                    "userStartKey should not be null!");
        }

        byte[] tempStartRowkeyByte;

        if (Bytes.compareTo(startRowkeyByte, regionStarKey) < 0) {
            tempStartRowkeyByte = regionStarKey;
        } else {
            tempStartRowkeyByte = startRowkeyByte;
        }
        return Bytes.toStringBinary(tempStartRowkeyByte);
    }

    @Override
    public InputSplitAssigner getInputSplitAssigner(InputSplit[] inputSplits) {
        return new DefaultInputSplitAssigner(inputSplits);
    }

    @Override
    public void openInternal(InputSplit inputSplit) throws IOException {
        HbaseInputSplit hbaseInputSplit = (HbaseInputSplit) inputSplit;
        byte[] startRow = Bytes.toBytesBinary(hbaseInputSplit.getStartkey());
        byte[] stopRow = Bytes.toBytesBinary(hbaseInputSplit.getEndKey());

        if(null == connection || connection.isClosed()){
            connection = HbaseHelper.getHbaseConnection(hbaseConfig, jobId, "reader");
        }

        openKerberos = HbaseHelper.openKerberos(hbaseConfig);

        table = connection.getTable(TableName.valueOf(tableName));
        scan = new Scan();
        scan.setStartRow(startRow);
        scan.setStopRow(stopRow);
        scan.setCaching(scanCacheSize);
        scan.setBatch(scanBatchSize);
        resultScanner = table.getScanner(scan);
    }

    @Override
    public boolean reachedEnd() throws IOException {
        next = resultScanner.next();
        return next == null;
    }

    @Override
    public Row nextRecordInternal(Row row) throws IOException {
        row = new Row(columnTypes.size());

        for (int i = 0; i < columnTypes.size(); ++i) {
            String columnType = columnTypes.get(i);
            String columnName = columnNames.get(i);
            String columnFormat = columnFormats.get(i);
            String columnValue = columnValues.get(i);
            Object col = null;
            byte[] bytes;

            try {
                if (StringUtils.isNotEmpty(columnValue)) {
                    // ??????
                    col = convertValueToAssignType(columnType, columnValue, columnFormat);
                } else {
                    if (columnName.equals("rowkey")) {
                        bytes = next.getRow();
                    } else {
                        byte [][] arr = nameMaps.get(columnName);
                        if(arr == null){
                            arr = new byte[2][];
                            String[] arr1 = columnName.split(":");
                            arr[0] = arr1[0].trim().getBytes();
                            arr[1] = arr1[1].trim().getBytes();
                            nameMaps.put(columnName,arr);
                        }
                        bytes = next.getValue(arr[0], arr[1]);
                    }
                    col = convertBytesToAssignType(columnType, bytes, columnFormat);
                }
                row.setField(i, col);
            } catch(Exception e) {
                throw new IOException("Couldn't read data:",e);
            }
        }
        return row;
    }

    @Override
    public void closeInternal() throws IOException {
        HbaseHelper.closeConnection(connection);

        if(openKerberos){
            KerberosUtil.clear(jobId);
        }
    }

    public Object convertValueToAssignType(String columnType, String constantValue,String dateformat) throws Exception {
        Object column  = null;
        if(org.apache.commons.lang3.StringUtils.isEmpty(constantValue)) {
            return column;
        }

        switch (columnType.toUpperCase()) {
            case "BOOLEAN":
                column = Boolean.valueOf(constantValue);
                break;
            case "SHORT":
            case "INT":
            case "LONG":
                column = NumberUtils.createBigDecimal(constantValue).toBigInteger();
                break;
            case "FLOAT":
            case "DOUBLE":
                column = new BigDecimal(constantValue);
                break;
            case "STRING":
                column = constantValue;
                break;
            case "DATE":
                column = DateUtils.parseDate(constantValue, new String[]{dateformat});
                break;
            default:
                throw new IllegalArgumentException("Unsupported columnType: " + columnType);
        }

        return column;
    }

    public Object convertBytesToAssignType(String columnType, byte[] byteArray,String dateformat) throws Exception {
        Object column = null;
        if(ArrayUtils.isEmpty(byteArray)) {
            return null;
        }

        switch (columnType.toUpperCase()) {
            case "BOOLEAN":
                column = Bytes.toBoolean(byteArray);
                break;
            case "SHORT":
                column = String.valueOf(Bytes.toShort(byteArray));
                break;
            case "INT":
                column = Bytes.toInt(byteArray);
                break;
            case "LONG":
                column = Bytes.toLong(byteArray);
                break;
            case "FLOAT":
                column = Bytes.toFloat(byteArray);
                break;
            case "DOUBLE":
                column = Bytes.toDouble(byteArray);
                break;
            case "STRING":
                column = new String(byteArray, encoding);
                break;
            case "BINARY_STRING":
                column = Bytes.toStringBinary(byteArray);
                break;
            case "DATE":
                String dateValue = Bytes.toStringBinary(byteArray);
                column = DateUtils.parseDate(dateValue, new String[]{dateformat});
                break;
            default:
                throw new IllegalArgumentException("Unsupported column type: " + columnType);
        }
        return column;
    }

}
