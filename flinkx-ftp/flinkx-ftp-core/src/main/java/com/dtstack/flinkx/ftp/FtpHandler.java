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

package com.dtstack.flinkx.ftp;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The concrete Ftp Utility class used for standard ftp
 *
 * Company: www.dtstack.com
 * @author huyifan.zju@163.com
 */
public class FtpHandler implements IFtpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(FtpHandler.class);

    private static final String DISCONNECT_FAIL_MESSAGE = "Failed to disconnect from ftp server";

    private FTPClient ftpClient = null;

    private static final String SP = "/";

    public FTPClient getFtpClient() {
        return ftpClient;
    }

    @Override
    public void loginFtpServer(String host, String username, String password, int port, int timeout, String connectMode) {
        ftpClient = new FTPClient();
        try {
            // ??????
            ftpClient.connect(host, port);
            // ??????
            ftpClient.login(username, password);
            // ???????????????ftp server???OS TYPE,FTPClient getSystemType()?????????????????????
            ftpClient.setConnectTimeout(timeout);
            ftpClient.setDataTimeout(timeout);
            if ("PASV".equals(connectMode)) {
                ftpClient.enterRemotePassiveMode();
                ftpClient.enterLocalPassiveMode();
            } else if ("PORT".equals(connectMode)) {
                ftpClient.enterLocalActiveMode();
            }
            int reply = ftpClient.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                ftpClient.disconnect();
                String message = String.format("???ftp???????????????????????????,???????????????????????????????????????: [%s]",
                        "message:host =" + host + ",username = " + username + ",port =" + port);
                LOG.error(message);
                throw new RuntimeException(message);
            }
            //????????????????????????
            String fileEncoding = System.getProperty("file.encoding");
            ftpClient.setControlEncoding(fileEncoding);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void logoutFtpServer() {
        if (ftpClient.isConnected()) {
            try {
                ftpClient.logout();
            } catch (IOException e) {
                LOG.error(DISCONNECT_FAIL_MESSAGE);
                throw new RuntimeException(e);
            }finally {
                if(ftpClient.isConnected()){
                    try {
                        ftpClient.disconnect();
                    } catch (IOException e) {
                        LOG.error(DISCONNECT_FAIL_MESSAGE);
                        throw new RuntimeException(e);
                    }
                }

            }
        }
    }

    @Override
    public boolean isDirExist(String directoryPath) {
        String originDir = null;
        try {
            originDir = ftpClient.printWorkingDirectory();
            return ftpClient.changeWorkingDirectory(new String(directoryPath.getBytes(), FTP.DEFAULT_CONTROL_ENCODING));
        } catch (IOException e) {
            String message = String.format("???????????????[%s]?????????I/O??????,????????????ftp????????????????????????", directoryPath);
            LOG.error(message);
            throw new RuntimeException(message, e);
        } finally {
            if(originDir != null) {
                try {
                    ftpClient.changeWorkingDirectory(originDir);
                } catch (IOException e) {
                    LOG.error(e.getMessage());
                }
            }
        }
    }

    @Override
    public boolean isFileExist(String filePath) {
        boolean isExitFlag = false;
        try {
            FTPFile[] ftpFiles = ftpClient.listFiles(new String(filePath.getBytes(),FTP.DEFAULT_CONTROL_ENCODING));
            if (ftpFiles.length == 1 && ftpFiles[0].isFile()) {
                isExitFlag = true;
            }
        } catch (IOException e) {
            String message = String.format("???????????????[%s] ???????????????I/O??????,????????????ftp????????????????????????", filePath);
            LOG.error(message);
            throw new RuntimeException(e);
        }
        return isExitFlag;
    }


    @Override
    public List<String> getFiles(String path) {
        List<String> sources = new ArrayList<>();
        if(isDirExist(path)) {
            if(!path.endsWith(SP)) {
                path = path + SP;
            }
            try {
                FTPFile[] ftpFiles = ftpClient.listFiles(new String(path.getBytes(),FTP.DEFAULT_CONTROL_ENCODING));
                if(ftpFiles != null) {
                    for(FTPFile ftpFile : ftpFiles) {
                        sources.addAll(getFiles(path + ftpFile.getName()));
                    }
                }
            } catch (IOException e) {
                LOG.error("", e);
                throw new RuntimeException(e);
            }

        } else if(isFileExist(path)) {
            sources.add(path);
            return sources;
        }

        return sources;
    }

    @Override
    public void mkDirRecursive(String directoryPath){
        StringBuilder dirPath = new StringBuilder();
        dirPath.append(IOUtils.DIR_SEPARATOR_UNIX);
        String[] dirSplit = StringUtils.split(directoryPath,IOUtils.DIR_SEPARATOR_UNIX);
        String message = String.format("????????????:%s???????????????,????????????ftp????????????????????????,????????????????????????", directoryPath);
        try {
            // ftp server???????????????????????????,????????????????????????
            for(String dirName : dirSplit){
                dirPath.append(dirName);
                boolean mkdirSuccess = mkDirSingleHierarchy(dirPath.toString());
                dirPath.append(IOUtils.DIR_SEPARATOR_UNIX);
                if(!mkdirSuccess){
                    throw new RuntimeException(message);
                }
            }
        } catch (IOException e) {
            message = String.format("%s, errorMessage:%s", message,
                    e.getMessage());
            LOG.error(message);
            throw new RuntimeException(message, e);
        }
    }


    private boolean mkDirSingleHierarchy(String directoryPath) throws IOException {
        boolean isDirExist = this.ftpClient
                .changeWorkingDirectory(directoryPath);
        // ??????directoryPath???????????????,?????????
        if (!isDirExist) {
            int replayCode = this.ftpClient.mkd(directoryPath);
            if (replayCode != FTPReply.COMMAND_OK
                    && replayCode != FTPReply.PATHNAME_CREATED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public OutputStream getOutputStream(String filePath) {
        try {
            this.printWorkingDirectory();
            String parentDir = filePath.substring(0,
                    StringUtils.lastIndexOf(filePath, IOUtils.DIR_SEPARATOR));
            this.ftpClient.changeWorkingDirectory(parentDir);
            this.printWorkingDirectory();
            OutputStream writeOutputStream = this.ftpClient
                    .appendFileStream(filePath);
            String message = String.format(
                    "??????FTP??????[%s]????????????????????????,???????????????%s????????????????????????????????????", filePath,
                    filePath);
            if (null == writeOutputStream) {
                throw new RuntimeException(message);
            }

            return writeOutputStream;
        } catch (IOException e) {
            String message = String.format(
                    "???????????? : [%s] ?????????,???????????????:[%s]????????????????????????????????????, errorMessage:%s",
                    filePath, filePath, e.getMessage());
            LOG.error(message);
            throw new RuntimeException(message, e);
        }
    }

    private void printWorkingDirectory() {
        try {
            LOG.info(String.format("current working directory:%s",
                    this.ftpClient.printWorkingDirectory()));
        } catch (Exception e) {
            LOG.warn(String.format("printWorkingDirectory error:%s",
                    e.getMessage()));
        }
    }

    @Override
    public void deleteAllFilesInDir(String dir, List<String> exclude) {
        if(isDirExist(dir)) {
            if(!dir.endsWith(SP)) {
                dir = dir + SP;
            }

            try {
                FTPFile[] ftpFiles = ftpClient.listFiles(new String(dir.getBytes(),FTP.DEFAULT_CONTROL_ENCODING));
                if(ftpFiles != null) {
                    for(FTPFile ftpFile : ftpFiles) {
                        if(CollectionUtils.isNotEmpty(exclude) && exclude.contains(ftpFile.getName())){
                            continue;
                        }
                        deleteAllFilesInDir(dir + ftpFile.getName(), exclude);
                    }
                }

                if(CollectionUtils.isEmpty(exclude)){
                    ftpClient.rmd(dir);
                }
            } catch (IOException e) {
                LOG.error("", e);
                throw new RuntimeException(e);
            }
        } else if(isFileExist(dir)) {
            try {
                ftpClient.deleteFile(dir);
            } catch (IOException e) {
                LOG.error("", e);
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public InputStream getInputStream(String filePath) {
        try {
            InputStream is = ftpClient.retrieveFileStream(new String(filePath.getBytes(),FTP.DEFAULT_CONTROL_ENCODING));
            return is;
        } catch (IOException e) {
            String message = String.format("???????????? : [%s] ?????????,??????????????????[%s]???????????????????????????????????????", filePath, filePath);
            LOG.error(message);
            throw new RuntimeException(message, e);
        }
    }

    @Override
    public List<String> listDirs(String path) {
        List<String> sources = new ArrayList<>();
        if(isDirExist(path)) {
            if(!path.endsWith(SP)) {
                path = path + SP;
            }

            try {
                FTPFile[] ftpFiles = ftpClient.listFiles(new String(path.getBytes(),FTP.DEFAULT_CONTROL_ENCODING));
                if(ftpFiles != null) {
                    for(FTPFile ftpFile : ftpFiles) {
                        sources.add(path + ftpFile.getName());
                    }
                }
            } catch (IOException e) {
                LOG.error("", e);
                throw new RuntimeException(e);
            }
        }

        return sources;
    }

    @Override
    public void rename(String oldPath, String newPath) throws IOException {
        ftpClient.rename(oldPath, newPath);
    }
}
