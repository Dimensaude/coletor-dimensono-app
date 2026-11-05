/*  Derived from Gadgetbridge (AGPL-3.0-or-later). */
package br.dimensono.bandsono.huawei.requests;

import java.util.List;

import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;
import br.dimensono.bandsono.huawei.packets.Notifications;

/** Switches phone notifications on the band off (0x02/0x04). */
public class SetNotificationStateRequest extends Request {

    public SetNotificationStateRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = Notifications.id;
        this.commandId = Notifications.NotificationStateRequest.id;
    }

    @Override
    protected boolean requestSupported() {
        return supportProvider.getDeviceState().supportsCommandForService(0x02, 0x04);
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        // Collection only: phone notifications on the band are always switched off
        boolean on = false;
        try {
            return new Notifications.NotificationStateRequest(paramsProvider, on).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }
}
