package com.devteam.mikufunbackend.service.serviceImpl;

import com.devteam.mikufunbackend.constant.Aria2Constant;
import com.devteam.mikufunbackend.dao.DownloadStatusDao;
import com.devteam.mikufunbackend.dao.ResourceInformationDao;
import com.devteam.mikufunbackend.entity.*;
import com.devteam.mikufunbackend.handle.ShellException;
import com.devteam.mikufunbackend.service.serviceInterface.Aria2Service;
import com.devteam.mikufunbackend.service.serviceInterface.DownLoadService;
import com.devteam.mikufunbackend.service.serviceInterface.TransferService;
import com.devteam.mikufunbackend.util.HttpClientUtil;
import com.devteam.mikufunbackend.util.ParamUtil;
import com.devteam.mikufunbackend.util.ResultUtil;
import com.devteam.mikufunbackend.util.ShellUtil;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Jackisome
 * @date 2021/10/3
 */
@Service
public class TransferServiceImpl implements TransferService {

    Logger logger = LoggerFactory.getLogger(TransferServiceImpl.class);

    @Autowired
    private DownloadStatusDao downloadStatusDao;

    @Autowired
    private ResourceInformationDao resourceInformationDao;

    @Autowired
    private Aria2Service aria2Service;

    @Autowired
    private DownLoadService downloadService;

    @Value("${shell.path}")
    private String shellPath;

    @Value("${dandanplay.url}")
    private String dandanPlayUrl;

    @Override
    public void transfer() throws IOException {
        logger.info("begin schedule task: transfer");
        List<Aria2StatusV0> aria2StatusV0s = aria2Service.getFileStatus(Aria2Constant.METHOD_TELL_STOPPED);
        aria2StatusV0s.addAll(aria2Service.getFileStatus(Aria2Constant.METHOD_TELL_ACTIVE));
        Set<String> gidSet = new HashSet<>(resourceInformationDao.findAllGid());
        for (Aria2StatusV0 aria2StatusV0 : aria2StatusV0s) {
            String gid = aria2StatusV0.getGid();
            if (!gidSet.contains(gid)) {
                logger.info("transfer files: {}", aria2StatusV0);
                if (Aria2Constant.downloadStatus.COMPLETE.getDescription().equals(aria2StatusV0.getStatus())
                        || (Aria2Constant.downloadStatus.ACTIVE.getDescription().equals(aria2StatusV0.getStatus()) && aria2StatusV0.getCompletedLength() == aria2StatusV0.getTotalLength())) {
                    for (Aria2FileV0 file : aria2StatusV0.getFiles()) {
                        if (!ResultUtil.getFileName(file.getPath()).equals(file.getPath())) {
                            logger.info("begin transfer resource, file: {}", file);
                            if (transferFile(file, gid)) {
                                logger.info("transfer file complete, gid: {}, file{}", gid, file);
                            } else {
                                logger.error("can not transfer file, gid: {}, file: {}", gid, file);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean transferFile(Aria2FileV0 aria2FileV0, String gid) throws IOException {
        String filePath = aria2FileV0.getPath();
        String fileName = ResultUtil.getFileName(filePath);
        String type = ResultUtil.getFileType(filePath);
        if (ResultUtil.getFileName(filePath).startsWith("[METADATA]")) {
            return true;
        }

        String[] cmd;
        String uuid = ParamUtil.getUUID();
        if (ParamUtil.validateType(type)) {
            // 进行资源转码
            cmd = new String[]{"bash", shellPath, "transfer-" + type, filePath, uuid};
            logger.info("transfer file to m3u8, fileName: {}", fileName);
            int exitValue = -1;
            try {
                exitValue = ShellUtil.runShellCommandSync("/docker", cmd, "/docker/transferLog");
            } catch (ShellException e) {
                logger.error(e.getMessage());
            }
            if (exitValue == 0) {
                logger.info("transfer file to m3u8 and ts file complete, fileName: {}", fileName);
                resourceInformationDao.addResourceInformation(generateResourceEntity(aria2FileV0, gid, uuid));
                downloadStatusDao.updateFinishTag(filePath);
                return true;
            } else {
                logger.error("transfer file to m3u8 and ts file fail, fileName: {}", fileName);
                return false;
            }
        } else {
            logger.error("unresolved file type, fileName: {}", fileName);
            return false;
        }
    }

    @Override
    public void cleanSourceFiles() throws IOException {
        List<DownloadStatusEntity> downloadStatusEntities = downloadStatusDao.findAbleDeleteResource();
        List<Aria2StatusV0> aria2StatusV0s = aria2Service.getFileStatus(Aria2Constant.METHOD_TELL_STOPPED);
        Set<String> gids = new HashSet<>();
        aria2StatusV0s.forEach(aria2StatusV0 -> gids.add(aria2StatusV0.getGid()));
        logger.info("begin to clean source file");
        downloadStatusEntities.forEach(downloadStatusEntity -> {
            String gid = downloadStatusEntity.getGid();
            if (gids.contains(gid)) {
                String filePath = downloadStatusEntity.getFilePath();
                try {
                    downloadService.remove(gid);
                } catch (IOException e) {
                    logger.error(e.toString());
                }
                try {
                    if (deleteFile(filePath)) {
                        downloadStatusDao.updateSourceDeleteTag(filePath);
                        logger.info("clean source file and update record in downloadStatus table, filePath: {}", filePath);
                    }
                } catch (IOException | InterruptedException e) {
                    logger.error(e.toString());
                }
            }
        });
    }

    @Override
    public boolean deleteFile(String path) throws IOException, InterruptedException {
        String[] cmd = new String[]{"bash", shellPath, "delete", path};
        logger.info("delete source file, path: {}", path);
        int exitValue = -1;
        try {
            exitValue = ShellUtil.runShellCommandSync("/docker", cmd, "/docker/deleteLog");
        } catch (ShellException e) {
            logger.error(e.getMessage());
        }
        if (exitValue == 0) {
            logger.info("delete source file complete, path: {}", path);
            return true;
        } else {
            logger.error("delete source file fail, filePath: {}", path);
            return false;
        }
    }

    @Override
    public List<ResourceMatchV0> matchResourceInformation(String fileName, String fileHash, int fileSize, int videoDuration) throws IOException {
        List<ResourceMatchV0> data = new ArrayList<>();
        DandanPlayMatchRequestV0 dandanPlayMatchRequestV0 = DandanPlayMatchRequestV0.builder()
                .fileName(ResultUtil.getFileNameWithoutExtensionName(fileName))
                .fileHash(fileHash)
                .fileSize(fileSize)
                .videoDuration(videoDuration)
                .matchMode("hashAndFileName")
                .build();
        logger.info("match file to resource information, request: {}", dandanPlayMatchRequestV0.toString());
        CloseableHttpResponse response = HttpClientUtil.sendPostAsJson(dandanPlayUrl + "/match", dandanPlayMatchRequestV0);
        DandanPlayMatchResponseV0 dandanPlayMatchResponseV0 = (DandanPlayMatchResponseV0) HttpClientUtil.convertJsonToObject(response, DandanPlayMatchResponseV0.class);
        System.out.println("matchresponse: " + dandanPlayMatchResponseV0.toString());
        if (dandanPlayMatchResponseV0.getErrorCode() != 0) {
            logger.error("invoke dandanplay match fail, errorMessage: {}", dandanPlayMatchResponseV0.getErrorMessage());
            return data;
        }
        List<DandanPlayMatchV0> dandanPlayMatchV0s = dandanPlayMatchResponseV0.getMatches();
        if (dandanPlayMatchV0s == null || dandanPlayMatchV0s.size() == 0) {
            logger.error("invoke dandanplay match, no matches, fileName: {}", fileName);
            return data;
        }
        dandanPlayMatchV0s.forEach(dandanPlayMatchV0 -> {
            ResourceMatchV0 resourceMatchV0 = ResourceMatchV0.builder()
                    .resourceId(dandanPlayMatchV0.getAnimeId())
                    .resourceName(dandanPlayMatchV0.getAnimeTitle())
                    .episodeTitle(dandanPlayMatchV0.getEpisodeTitle())
                    .type(dandanPlayMatchV0.getType())
                    .episodeId(dandanPlayMatchV0.getEpisodeId())
                    .shift(dandanPlayMatchV0.getShift())
                    .build();
            data.add(resourceMatchV0);
        });
        return data;
    }

    private long getVideoDuration(String filePath) {
        long duration = 0L;
        File video = new File(filePath);
        FFmpegFrameGrabber ff = new FFmpegFrameGrabber(video);
        logger.info("get video duration, video: {}", video);
        try {
            ff.start();
            duration = ff.getLengthInTime() / (1000 * 1000);
            ff.stop();
        } catch (FrameGrabber.Exception e) {
            logger.error(e.toString());
        }
        return duration;
    }

    private String makeResourceImage(String filePath, String uuid) throws IOException {
        String[] cmd = new String[]{"bash", shellPath, "make-image", filePath, uuid};
        logger.info("make image, filePath: {}", filePath);
        int exitValue = -1;
        try {
            exitValue = ShellUtil.runShellCommandSync("/docker", cmd, "/docker/makeImageLog");
        } catch (ShellException e) {
            logger.error(e.getMessage());
        }
        if (exitValue == 0) {
            logger.info("make image complete, filePath: {}", filePath);
            return "/docker/image/" + uuid + ".jpg";
        } else {
            logger.error("make image fail, filePath: {}", filePath);
            return "docker/image/default.jpg";
        }
    }

    private String getResourceMd5(String filePath) {
        File file = new File(filePath);
        if (file.exists()) {
            logger.info("get resource md5, filePath: {}", filePath);
            byte[] first16MBytes = new byte[16 * 1024 * 1024];
            try (InputStream inputStream = new FileInputStream(file)) {
                inputStream.read(first16MBytes);
            } catch (IOException e) {
                logger.error(e.toString());
            }
            try {
                MessageDigest md5 = MessageDigest.getInstance("MD5");
                md5.update(first16MBytes);
                byte[] byteArray = md5.digest();

                BigInteger bigInt = new BigInteger(1, byteArray);
                // 参数16表示16进制
                StringBuilder result = new StringBuilder(bigInt.toString(16));
                // 不足32位高位补零
                while (result.length() < 32) {
                    result.insert(0, "0");
                }
                return result.toString();
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
        }
        return "";
    }

    private ResourceEntity generateResourceEntity(Aria2FileV0 aria2FileV0, String gid, String uuid) throws IOException {
        String filePath = aria2FileV0.getPath();
        String fileName = ResultUtil.getFileName(filePath);
        // 获取资源时长
        int videoDuration = (int) getVideoDuration(filePath);

        // 获取资源前16M字节的32位MD5值
        String md5 = getResourceMd5(filePath);

        // 获取资源截图
        String imageUrl = makeResourceImage(filePath, uuid);

        // 获取资源对应的番剧集数和弹幕
        List<ResourceMatchV0> resourceMatchV0s = matchResourceInformation(fileName, md5, aria2FileV0.getLength(), videoDuration);

        ResourceEntity resourceEntity = ResourceEntity.builder()
                .fileName(fileName)
                .fileDirectory("/docker/resource/" + uuid + "/index.m3u8")
                .fileHash(md5)
                .fileSize(aria2FileV0.getLength())
                .videoDuration(videoDuration)
                .imageUrl(imageUrl)
                .gid(gid)
                .build();
        if (resourceMatchV0s.size() != 0) {
            ResourceMatchV0 matchV0 = resourceMatchV0s.get(0);
            resourceEntity.setExactMatch(resourceMatchV0s.size() == 1? 1: 0)
                    .setResourceId(matchV0.getResourceId())
                    .setResourceName(matchV0.getResourceName())
                    .setEpisodeTitle(matchV0.getEpisodeTitle())
                    .setType(matchV0.getType())
                    .setEpisodeId(matchV0.getEpisodeId())
                    .setDanmakuShift(matchV0.getShift());
        }
        return resourceEntity;
    }
}
