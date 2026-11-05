/*  Copyright (C) 2024 Damien Gaignon

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

import br.dimensono.bandsono.log.Logger;
import br.dimensono.bandsono.log.LoggerFactory;

import java.util.List;

import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.packets.FitnessData;
import br.dimensono.bandsono.huawei.packets.FitnessData.NotifyRestHeartRate;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;

public class SendNotifyRestHeartRateCapabilityRequest extends Request {
    private static final Logger LOG = LoggerFactory.getLogger(SendNotifyRestHeartRateCapabilityRequest.class);

    public SendNotifyRestHeartRateCapabilityRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = FitnessData.id;
        this.commandId = NotifyRestHeartRate.id;
    }

    @Override
    protected boolean requestSupported() {
        return supportProvider.getDeviceState().supportsFitnessRestHeartRate();
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new NotifyRestHeartRate.Request(paramsProvider).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() {
        LOG.debug("handle Send Workout HeartRate Capability Request");
    }
}
