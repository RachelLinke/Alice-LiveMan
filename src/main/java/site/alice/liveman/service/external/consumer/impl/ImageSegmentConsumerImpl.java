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

package site.alice.liveman.service.external.consumer.impl;

import lombok.extern.slf4j.Slf4j;
import site.alice.liveman.customlayout.CustomLayout;
import site.alice.liveman.customlayout.impl.ImageSegmentBlurLayout;
import site.alice.liveman.mediaproxy.MediaProxyManager;
import site.alice.liveman.mediaproxy.proxytask.MediaProxyTask;
import site.alice.liveman.model.AccountInfo;
import site.alice.liveman.model.BroadcastConfig;
import site.alice.liveman.model.VideoInfo;
import site.alice.liveman.service.broadcast.BroadcastTask;
import site.alice.liveman.service.external.consumer.ImageSegmentConsumer;

import java.awt.image.BufferedImage;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class ImageSegmentConsumerImpl implements ImageSegmentConsumer {

    private BroadcastTask broadcastTask;

    public ImageSegmentConsumerImpl(BroadcastTask broadcastTask) {
        this.broadcastTask = broadcastTask;
    }

    @Override
    public void accept(BufferedImage resultImage, BufferedImage originalImage) {
        VideoInfo videoInfo = broadcastTask.getVideoInfo();
        AccountInfo broadcastAccount = broadcastTask.getBroadcastAccount();
        BroadcastConfig broadcastConfig = videoInfo.getBroadcastConfig();
        try {
            double scale = 720.0 / originalImage.getHeight();
            CopyOnWriteArrayList<CustomLayout> customLayouts = broadcastConfig.getLayouts();
            customLayouts.removeIf(customLayout -> customLayout instanceof ImageSegmentBlurLayout);
            ImageSegmentBlurLayout imageSegmentBlurLayout = new ImageSegmentBlurLayout();
            imageSegmentBlurLayout.setIndex(10);
            imageSegmentBlurLayout.setImage(resultImage);
            imageSegmentBlurLayout.setVideoInfo(videoInfo);
            imageSegmentBlurLayout.setX(0);
            imageSegmentBlurLayout.setY(0);
            imageSegmentBlurLayout.setWidth((int) (originalImage.getWidth() * scale));
            imageSegmentBlurLayout.setHeight((int) (originalImage.getHeight() * scale));
            customLayouts.add(imageSegmentBlurLayout);
            broadcastConfig.setCachedBlurBytes(null);
            VideoInfo lowVideoInfo = broadcastTask.getLowVideoInfo();
            if (lowVideoInfo != null) {
                lowVideoInfo.getBroadcastConfig().setLayouts(customLayouts);
                lowVideoInfo.getBroadcastConfig().setCachedBlurBytes(null);
            }
            log.info("Accepted image segment[videoId=" + videoInfo.getVideoUnionId() + "]");
        } catch (Throwable e) {
            log.error("处理图像分割失败[videoId=" + videoInfo.getVideoUnionId() + "]", e);
        }
    }
}
