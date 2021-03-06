package cc.blynk.server.application.handlers.main.logic.dashboard.device;

import cc.blynk.server.Holder;
import cc.blynk.server.application.handlers.main.auth.AppStateHolder;
import cc.blynk.server.core.BlockingIOProcessor;
import cc.blynk.server.core.dao.ReportingDiskDao;
import cc.blynk.server.core.dao.SessionDao;
import cc.blynk.server.core.dao.TokenManager;
import cc.blynk.server.core.model.DashBoard;
import cc.blynk.server.core.model.auth.Session;
import cc.blynk.server.core.model.device.Device;
import cc.blynk.server.core.protocol.exceptions.IllegalCommandException;
import cc.blynk.server.core.protocol.model.messages.StringMessage;
import cc.blynk.utils.ArrayUtil;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static cc.blynk.server.internal.CommonByteBufUtil.ok;
import static cc.blynk.utils.StringUtils.split2;

/**
 * The Blynk Project.
 * Created by Dmitriy Dumanskiy.
 * Created on 01.02.16.
 */
public class DeleteDeviceLogic {

    private static final Logger log = LogManager.getLogger(DeleteDeviceLogic.class);

    private final TokenManager tokenManager;
    private final SessionDao sessionDao;
    private final ReportingDiskDao reportingDao;
    private final BlockingIOProcessor blockingIOProcessor;

    public DeleteDeviceLogic(Holder holder) {
        this.tokenManager = holder.tokenManager;
        this.sessionDao = holder.sessionDao;
        this.reportingDao = holder.reportingDiskDao;
        this.blockingIOProcessor = holder.blockingIOProcessor;
    }

    public void messageReceived(ChannelHandlerContext ctx, AppStateHolder state, StringMessage message) {
        String[] split = split2(message.body);

        if (split.length < 2) {
            throw new IllegalCommandException("Wrong income message format.");
        }

        int dashId = Integer.parseInt(split[0]);
        int deviceId = Integer.parseInt(split[1]);

        DashBoard dash = state.user.profile.getDashByIdOrThrow(dashId);

        log.debug("Deleting device with id {}.", deviceId);

        int existingDeviceIndex = dash.getDeviceIndexById(deviceId);
        Device device = dash.devices[existingDeviceIndex];
        tokenManager.deleteDevice(device);
        Session session = sessionDao.userSession.get(state.userKey);
        session.closeHardwareChannelByDeviceId(dashId, deviceId);

        dash.devices = ArrayUtil.remove(dash.devices, existingDeviceIndex, Device.class);
        dash.eraseValuesForDevice(deviceId);
        try {
            dash.deleteDeviceFromObjects(deviceId);
        } catch (Exception e) {
            log.warn("Error erasing widget device. Reason : {}", e.getMessage());
        }
        dash.updatedAt = System.currentTimeMillis();
        state.user.lastModifiedTs = dash.updatedAt;

        blockingIOProcessor.executeHistory(() -> {
            try {
                reportingDao.delete(state.user, dashId, deviceId);
            } catch (Exception e) {
                log.warn("Error removing device data. Reason : {}.", e.getMessage());
            }
        });

        ctx.writeAndFlush(ok(message.id), ctx.voidPromise());
    }

}
