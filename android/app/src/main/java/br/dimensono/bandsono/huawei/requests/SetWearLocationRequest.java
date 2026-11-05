/*  Derived from Gadgetbridge (AGPL-3.0-or-later). */
package br.dimensono.bandsono.huawei.requests;

import java.util.List;

import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;
import br.dimensono.bandsono.huawei.packets.DeviceConfig;

/** Which wrist the band is worn on (the band tunes its sensors to it): left = 1, right = 0. */
public class SetWearLocationRequest extends Request {

    public SetWearLocationRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.WearLocationRequest.id;
    }

    @Override
    protected boolean requestSupported() {
        return supportProvider.getDeviceState().supportsWearLocation();
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        boolean left = supportProvider.getSetting(HuaweiSupportProvider.PREF_WEAR_LEFT, true);
        try {
            return new DeviceConfig.WearLocationRequest(paramsProvider, (byte) (left ? 1 : 0)).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }
}
