/*  Derived from Gadgetbridge (AGPL-3.0-or-later). */
package br.dimensono.bandsono.huawei.requests;

import java.util.List;

import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;
import br.dimensono.bandsono.huawei.packets.Notifications;

/**
 * Whether the band shows phone notifications while it is not on the wrist (0x02/0x08).
 * Gadgetbridge's default: deliver them anyway (the packet carries the opposite of "do not disturb").
 */
public class SetWearMessagePushRequest extends Request {

    public SetWearMessagePushRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = Notifications.id;
        this.commandId = Notifications.WearMessagePushRequest.id;
    }

    @Override
    protected boolean requestSupported() {
        return supportProvider.getDeviceState().supportsCommandForService(0x02, 0x08);
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new Notifications.WearMessagePushRequest(paramsProvider, false).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }
}
