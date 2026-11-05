/*  Copyright (C) 2024 Damien Gaignon, Martin.JM

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

import br.dimensono.bandsono.huawei.HuaweiConstants;
import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.packets.FitnessData;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;

public class SetAutomaticHeartrateRequest extends Request {
    private static final Logger LOG = LoggerFactory.getLogger(SetAutomaticHeartrateRequest.class);

    public SetAutomaticHeartrateRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = FitnessData.id;
        this.commandId = FitnessData.EnableAutomaticHeartrate.id;
        this.addToResponse = false;
    }

    @Override
    protected boolean requestSupported() {
        // Always send when the band did not report its command list
        return !supportProvider.getDeviceState().hasCommandList() || supportProvider.getDeviceState().supportsHeartRate();
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        boolean automaticHeartRateEnabled = supportProvider.getSetting(HuaweiSupportProvider.PREF_HR_AUTO, true);
        boolean realtimeHeartRateEnabled = supportProvider.getSetting(HuaweiSupportProvider.PREF_HR_REALTIME, true);
        if (automaticHeartRateEnabled)
            LOG.info("Attempting to enable automatic heart rate");
        else
            LOG.info("Attempting to disable automatic heart rate");
        boolean realtime = realtimeHeartRateEnabled && this.supportProvider.getDeviceState().supportsRealtimeHeartRate();
        if (!automaticHeartRateEnabled)
            supportProvider.reportHeartRateMode("desligada");
        else if (realtime)
            supportProvider.reportHeartRateMode("tempo real");
        else
            supportProvider.reportHeartRateMode(realtimeHeartRateEnabled ? "inteligente (sem tempo real)" : "inteligente");
        if (realtime) {
            try {
                return new FitnessData.EnableRealtimeHeartRate.Request(paramsProvider, automaticHeartRateEnabled).serialize();
            } catch (HuaweiPacket.CryptoException e) {
                throw new RequestCreationException(e);
            }
        } else {
            try {
                return new FitnessData.EnableAutomaticHeartrate.Request(paramsProvider, automaticHeartRateEnabled).serialize();
            } catch (HuaweiPacket.CryptoException e) {
                throw new RequestCreationException(e);
            }
        }
    }
}
