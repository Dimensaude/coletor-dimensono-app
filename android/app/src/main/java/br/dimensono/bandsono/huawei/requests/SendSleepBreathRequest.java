/*  Copyright (C) 2024 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package br.dimensono.bandsono.huawei.requests;

import java.util.List;

import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;
import br.dimensono.bandsono.huawei.packets.Breath;

/** Turns the band's sleep breathing monitoring (breathing irregularities during sleep) on or off. */
public class SendSleepBreathRequest extends Request {

    public SendSleepBreathRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = Breath.id;
        this.commandId = Breath.SleepBreath.id;
    }

    @Override
    protected boolean requestSupported() {
        return supportProvider.getDeviceState().supportsSleepBreath();
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        boolean on = supportProvider.getSetting(HuaweiSupportProvider.PREF_SLEEP_BREATH, true);
        try {
            return new Breath.SleepBreath.Request(paramsProvider, (byte) (on ? 2 : 0)).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }
}
