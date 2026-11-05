/*  Derived from Gadgetbridge (AGPL-3.0-or-later). */
package br.dimensono.bandsono.huawei.requests;

import java.util.List;

import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;
import br.dimensono.bandsono.huawei.packets.DeviceConfig;

/** "Raise to wake": whether lifting/turning the wrist lights the band's screen (off by default here). */
public class SetActivateOnLiftRequest extends Request {

    public SetActivateOnLiftRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.ActivateOnLiftRequest.id;
    }

    @Override
    protected boolean requestSupported() {
        return supportProvider.getDeviceState().supportsActivateOnLift();
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        boolean activate = false; // fixed: raise-to-wake stays off
        try {
            return new DeviceConfig.ActivateOnLiftRequest(paramsProvider, activate).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }
}
