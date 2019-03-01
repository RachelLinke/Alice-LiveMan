/*
 * <Alice LiveMan>
 * Copyright (C) <2018>  <NekoSunflower>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package site.alice.liveman.service.broadcast;

import com.keypoint.PngEncoderB;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import site.alice.liveman.customlayout.CustomLayout;
import site.alice.liveman.event.MediaProxyEvent;
import site.alice.liveman.event.MediaProxyEventListener;
import site.alice.liveman.jenum.VideoBannedTypeEnum;
import site.alice.liveman.mediaproxy.MediaProxyManager;
import site.alice.liveman.mediaproxy.proxytask.MediaProxyTask;
import site.alice.liveman.model.*;
import site.alice.liveman.service.BroadcastServerService;
import site.alice.liveman.service.MediaHistoryService;
import site.alice.liveman.service.live.LiveServiceFactory;
import site.alice.liveman.utils.BilibiliApiUtil;
import site.alice.liveman.utils.FfmpegUtil;
import site.alice.liveman.utils.ProcessUtil;
import site.alice.liveman.utils.ThreadPoolUtil;

import javax.annotation.PostConstruct;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class BroadcastServiceManager implements ApplicationContextAware {
    private Map<String, BroadcastService> broadcastServiceMap;
    @Autowired
    private LiveManSetting                liveManSetting;
    @Autowired
    private BilibiliApiUtil               bilibiliApiUtil;
    @Autowired
    private MediaHistoryService           mediaHistoryService;
    @Autowired
    private LiveServiceFactory            liveServiceFactory;
    @Autowired
    private BroadcastServerService        broadcastServerService;

    @PostConstruct
    public void init() {
        MediaProxyManager.addListener(new MediaProxyEventListener() {
            @Override
            public void onProxyStart(MediaProxyEvent e) {
                VideoInfo videoInfo = e.getMediaProxyTask().getVideoInfo();
                if (videoInfo != null) {
                    if (videoInfo.getChannelInfo() == null) {
                        return;
                    }
                    BroadcastTask broadcastTask;
                    if (videoInfo.getBroadcastTask() == null) {
                        broadcastTask = new BroadcastTask(videoInfo);
                        if (!videoInfo.setBroadcastTask(broadcastTask)) {
                            BroadcastTask currentBroadcastTask = videoInfo.getBroadcastTask();
                            try {
                                log.warn("试图创建推流任务的媒体资源已存在推流任务[roomId={}]，这是不正常的意外情况，将尝试终止已存在的推流任务[videoId={}]", currentBroadcastTask.getBroadcastAccount().getRoomId(), videoInfo.getVideoId());
                                if (!currentBroadcastTask.terminateTask()) {
                                    log.warn("终止转播任务失败：CAS操作失败");
                                }
                            } catch (Throwable throwable) {
                                log.error("启动推流任务时发生异常", throwable);
                            }
                        }
                    } else {
                        broadcastTask = videoInfo.getBroadcastTask();
                    }
                    ThreadPoolUtil.execute(broadcastTask);
                }
            }

            @Override
            public void onProxyStop(MediaProxyEvent e) {
                VideoInfo videoInfo = e.getMediaProxyTask().getVideoInfo();
                if (videoInfo != null) {
                    if (videoInfo.getChannelInfo() == null) {
                        return;
                    }
                    BroadcastTask broadcastTask = videoInfo.getBroadcastTask();
                    if (broadcastTask != null) {
                        AccountInfo broadcastAccount = broadcastTask.getBroadcastAccount();
                        if (broadcastAccount != null) {
                            broadcastAccount.removeCurrentVideo(videoInfo);
                            videoInfo.removeBroadcastTask(broadcastTask);
                            broadcastTask.terminateTask();
                        }
                    }
                }
            }
        });
    }

    public BroadcastTask createSingleBroadcastTask(VideoInfo videoInfo, AccountInfo broadcastAccount) throws Exception {
        if (broadcastAccount.setCurrentVideo(videoInfo)) {
            try {
                Map<String, MediaProxyTask> executedProxyTaskMap = MediaProxyManager.getExecutedProxyTaskMap();
                // 如果要推流的媒体已存在，则直接创建推流任务
                MediaProxyTask mediaProxyTask = executedProxyTaskMap.get(videoInfo.getVideoId());
                if (mediaProxyTask != null) {
                    videoInfo = mediaProxyTask.getVideoInfo();
                    BroadcastTask broadcastTask = new BroadcastTask(videoInfo, broadcastAccount);
                    if (!videoInfo.setBroadcastTask(broadcastTask)) {
                        throw new RuntimeException("此媒体已在推流任务列表中，无法添加");
                    }
                    ThreadPoolUtil.execute(broadcastTask);
                    return broadcastTask;
                } else {
                    // 创建直播流代理任务
                    BroadcastTask broadcastTask = new BroadcastTask(videoInfo, broadcastAccount);
                    if (!videoInfo.setBroadcastTask(broadcastTask)) {
                        throw new RuntimeException("此媒体已在推流任务列表中，无法添加");
                    }
                    mediaProxyTask = MediaProxyManager.createProxy(videoInfo);
                    if (mediaProxyTask == null) {
                        throw new RuntimeException("MediaProxyTask创建失败");
                    }
                    return broadcastTask;
                }
            } catch (Exception e) {
                // 操作失败，释放刚才获得的直播间资源
                broadcastAccount.removeCurrentVideo(videoInfo);
                throw e;
            }
        } else {
            throw new RuntimeException("无法创建转播任务，直播间已被节目[" + broadcastAccount.getCurrentVideo().getTitle() + "]占用！");
        }
    }

    public AccountInfo getBroadcastAccount(VideoInfo videoInfo) {
        ChannelInfo channelInfo = videoInfo.getChannelInfo();
        String defaultAccountId = channelInfo.getDefaultAccountId();
        if (defaultAccountId != null) {
            AccountInfo accountInfo = liveManSetting.findByAccountId(defaultAccountId);
            String logInfo = "频道[" + channelInfo.getChannelName() + "], videoId=" + videoInfo.getVideoId() + "的默认直播间[" + defaultAccountId + "]";
            if (accountInfo == null) {
                log.info(logInfo + "的账号信息不存在");
            } else if (accountInfo.isDisable()) {
                log.info(logInfo + "的账号信息不可用");
            } else if (!accountInfo.setCurrentVideo(videoInfo)) {
                log.info(logInfo + "已被占用[currentVideo=" + accountInfo.getCurrentVideo().getVideoId() + "]");
            } else {
                return accountInfo;
            }
        }
        if (channelInfo.isAutoBalance()) {
            /* 默认直播间不可用或没有设置默认 */
            Set<AccountInfo> accounts = liveManSetting.getAccounts();
            for (AccountInfo accountInfo : accounts) {
                if (accountInfo.isJoinAutoBalance() && !accountInfo.isDisable() && accountInfo.setCurrentVideo(videoInfo)) {
                    return accountInfo;
                }
            }
        }
        log.info("频道[" + channelInfo.getChannelName() + "], videoId=" + videoInfo.getVideoId() + "没有找到可以推流的直播间");
        return null;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        broadcastServiceMap = applicationContext.getBeansOfType(BroadcastService.class);
    }

    public BroadcastService getBroadcastService(String accountSite) {
        for (BroadcastService broadcastService : broadcastServiceMap.values()) {
            if (broadcastService.isMatch(accountSite)) {
                return broadcastService;
            }
        }
        throw new BeanDefinitionStoreException("没有找到可以推流到[" + accountSite + "]的BroadcastService");
    }

    public class CustomLayoutTask implements Runnable {
        private static final int       fps = 5;
        private              VideoInfo videoInfo;
        private              Process   process;

        public CustomLayoutTask(VideoInfo videoInfo, Process process) {
            this.videoInfo = videoInfo;
            this.process = process;
        }

        @Override
        public void run() {
            BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setBackground(new Color(0, 0, 0, 0));
            log.info("CustomLayoutTask已启动[videoId=" + videoInfo.getVideoId() + "]");
            while (process.isAlive()) {
                long startTime = System.nanoTime();
                graphics.clearRect(0, 0, image.getWidth(), image.getHeight());
                Set<CustomLayout> customLayoutList = videoInfo.getCropConf().getLayouts();
                if (CollectionUtils.isNotEmpty(customLayoutList)) {
                    for (CustomLayout customLayout : customLayoutList) {
                        try {
                            customLayout.paintLayout(graphics);
                        } catch (Exception e) {
                            log.error(customLayout.getClass().getName() + "[videoId=" + videoInfo.getVideoId() + "]渲染出错", e);
                        }
                    }
                }
                try {
                    PngEncoderB pngEncoderB = new PngEncoderB();
                    pngEncoderB.setEncodeAlpha(true);
                    pngEncoderB.setImage(image);
                    OutputStream outputStream = process.getOutputStream();
                    outputStream.write(pngEncoderB.pngEncode());
                    outputStream.flush();
                    long dt = (System.nanoTime() - startTime) / 1000000;
                    Thread.sleep(Math.max(0, (1000 / fps) - dt));
                } catch (Exception e) {
                    log.error("无法输出图像数据到管道[videoId=" + videoInfo.getVideoId() + "]", e);
                }
            }
            log.info("推流进程已结束[videoId=" + videoInfo.getVideoId() + "]，CustomLayoutTask自动退出...");
        }
    }

    public class BroadcastTask implements Runnable {

        private VideoInfo   videoInfo;
        private long        pid;
        private AccountInfo broadcastAccount;
        private boolean     terminate;
        private boolean     singleTask;

        public BroadcastTask(VideoInfo videoInfo, AccountInfo broadcastAccount) {
            this.videoInfo = videoInfo;
            this.broadcastAccount = broadcastAccount;
            singleTask = true;
        }

        public BroadcastTask(VideoInfo videoInfo) {
            this.videoInfo = videoInfo;
        }

        public VideoInfo getVideoInfo() {
            return videoInfo;
        }

        public long getPid() {
            return pid;
        }

        public AccountInfo getBroadcastAccount() {
            return broadcastAccount;
        }

        @Override
        public synchronized void run() {
            // 任务第一次启动时尝试用默认的转播账号进行一次转播
            if (!singleTask) {
                ChannelInfo channelInfo = videoInfo.getChannelInfo();
                if (channelInfo != null) {
                    String defaultAccountId = channelInfo.getDefaultAccountId();
                    if (defaultAccountId != null) {
                        AccountInfo accountInfo = liveManSetting.findByAccountId(defaultAccountId);
                        if (accountInfo != null) {
                            accountInfo.setDisable(false);
                        }
                    }
                }
            }
            Map<String, MediaProxyTask> executedProxyTaskMap = MediaProxyManager.getExecutedProxyTaskMap();
            while (executedProxyTaskMap.containsKey(videoInfo.getVideoId()) && !terminate) {
                try {
                    if (!singleTask) {
                        broadcastAccount = BroadcastServiceManager.this.getBroadcastAccount(videoInfo);
                        if (broadcastAccount == null) {
                            Thread.sleep(5000);
                            continue;
                        }
                        MediaHistory mediaHistory = mediaHistoryService.getMediaHistory(videoInfo.getVideoId());
                        if (mediaHistory == null || !mediaHistory.isPostDynamic()) {
                            bilibiliApiUtil.postDynamic(broadcastAccount);
                            if (mediaHistory != null) {
                                mediaHistory.setPostDynamic(true);
                            }
                        }
                    }
                    while (broadcastAccount.getCurrentVideo() == videoInfo && !broadcastAccount.isDisable()) {
                        try {
                            VideoInfo currentVideo = broadcastAccount.getCurrentVideo();
                            BroadcastService broadcastService = getBroadcastService(broadcastAccount.getAccountSite());
                            String broadcastAddress = broadcastService.getBroadcastAddress(broadcastAccount);
                            if (broadcastAccount.isAutoRoomTitle()) {
                                broadcastService.setBroadcastSetting(broadcastAccount, videoInfo.getTitle(), null);
                            }
                            String ffmpegCmdLine;
                            // 如果是区域打码或自定义的，创建低分辨率媒体代理服务
                            VideoInfo lowVideoInfo = null;
                            if (currentVideo.getCropConf().getVideoBannedType() == VideoBannedTypeEnum.AREA_SCREEN || currentVideo.getCropConf().getVideoBannedType() == VideoBannedTypeEnum.CUSTOM_SCREEN) {
                                MediaProxyTask mediaProxyTask = executedProxyTaskMap.get(currentVideo.getVideoId() + "_low");
                                if (mediaProxyTask != null) {
                                    lowVideoInfo = mediaProxyTask.getVideoInfo();
                                } else {
                                    lowVideoInfo = liveServiceFactory.getLiveService(currentVideo.getVideoInfoUrl().toString()).getLiveVideoInfo(currentVideo.getVideoInfoUrl(), null, "720");
                                    if (lowVideoInfo == null) {
                                        throw new RuntimeException("获取低清晰度视频源信息失败");
                                    }
                                    lowVideoInfo.setVideoId(currentVideo.getVideoId() + "_low");
                                    MediaProxyManager.createProxy(lowVideoInfo);
                                }
                                lowVideoInfo.setAudioBanned(currentVideo.isAudioBanned());
                                lowVideoInfo.setCropConf(currentVideo.getCropConf());
                                ffmpegCmdLine = FfmpegUtil.buildFfmpegCmdLine(lowVideoInfo, broadcastAddress);
                            } else {
                                // 如果不是区域打码了自动终止创建的低清晰度媒体代理任务
                                MediaProxyTask mediaProxyTask = executedProxyTaskMap.get(videoInfo.getVideoId() + "_low");
                                if (mediaProxyTask != null) {
                                    mediaProxyTask.terminate();
                                    mediaProxyTask.waitForTerminate();
                                }
                                ffmpegCmdLine = FfmpegUtil.buildFfmpegCmdLine(currentVideo, broadcastAddress);
                            }
                            pid = ProcessUtil.createProcess(ffmpegCmdLine, currentVideo.getVideoId());
                            log.info("[" + broadcastAccount.getRoomId() + "@" + broadcastAccount.getAccountSite() + ", videoId=" + currentVideo.getVideoId() + "]推流进程已启动[PID:" + pid + "][" + ffmpegCmdLine.replace("\t", " ") + "]");
                            if (currentVideo.getCropConf().getVideoBannedType() == VideoBannedTypeEnum.CUSTOM_SCREEN) {
                                // 先等待2秒保证ffmpeg已启动
                                Thread.sleep(2000);
                                // 启动自定义视图渲染线程
                                Process process = ProcessUtil.getProcess(pid);
                                if (process != null && process.isAlive()) {
                                    ThreadPoolUtil.execute(new CustomLayoutTask(currentVideo, process));
                                }
                            }
                            // 等待进程退出或者任务结束
                            while (broadcastAccount.getCurrentVideo() != null && !ProcessUtil.waitProcess(pid, 1000)) ;
                            // 杀死进程
                            ProcessUtil.killProcess(pid);
                            if (lowVideoInfo != null) {
                                broadcastServerService.releaseServer(lowVideoInfo);
                            }
                            log.info("[" + broadcastAccount.getRoomId() + "@" + broadcastAccount.getAccountSite() + ", videoId=" + currentVideo.getVideoId() + "]推流进程已终止PID:" + pid);
                        } catch (Throwable e) {
                            log.error("startBroadcast failed", e);
                        }
                        try {
                            if (!terminate) {
                                Thread.sleep(1000);
                            }
                        } catch (InterruptedException ignore) {
                        }
                    }
                    // 终止推流时自动终止创建的低清晰度媒体代理任务
                    MediaProxyTask mediaProxyTask = executedProxyTaskMap.get(videoInfo.getVideoId() + "_low");
                    if (mediaProxyTask != null) {
                        mediaProxyTask.terminate();
                        // 这里需要等待任务停止
                        mediaProxyTask.waitForTerminate();
                    }
                    broadcastAccount.removeCurrentVideo(videoInfo);
                    if (broadcastAccount.isDisable() && singleTask) {
                        log.warn("手动推流的直播账号[" + broadcastAccount.getAccountId() + "]不可用，已终止推流任务。");
                        terminate = true;
                        break;
                    }
                } catch (Throwable e) {
                    log.error("startBroadcast failed", e);
                }
                try {
                    if (!terminate) {
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException ignore) {
                }
            }
            if (videoInfo.getBroadcastTask() != null && !videoInfo.removeBroadcastTask(this)) {
                log.warn("警告：无法移除[videoId=" + videoInfo.getVideoId() + "]的推流任务，CAS操作失败");
            }
        }

        public boolean terminateTask() {
            if (broadcastAccount != null) {
                log.info("强制终止节目[" + videoInfo.getTitle() + "][videoId=" + videoInfo.getVideoId() + "]的推流任务[roomId=" + broadcastAccount.getRoomId() + "]");
                ThreadPoolUtil.schedule(() -> {
                    if (broadcastAccount.getCurrentVideo() == null) {
                        getBroadcastService(broadcastAccount.getAccountSite()).stopBroadcast(broadcastAccount, true);
                    }
                }, 2, TimeUnit.MINUTES);
                if (!broadcastAccount.removeCurrentVideo(videoInfo)) {
                    log.error("无法移除账号[" + broadcastAccount.getAccountId() + "]正在转播的节目[" + broadcastAccount.getCurrentVideo().getVideoId() + "]，目标节目与预期节目[" + videoInfo.getVideoId() + "]不符");
                    return false;
                }
            }
            terminate = true;
            videoInfo.removeBroadcastTask(this);
            ProcessUtil.killProcess(pid);
            return true;
        }

        public synchronized void waitForTerminate() {

        }
    }

}
