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

import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.packets.FitnessData;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;

public class SetAutomaticSpoRequest extends Request {
    private static final Logger LOG = LoggerFactory.getLogger(SetAutomaticSpoRequest.class);

    public SetAutomaticSpoRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = FitnessData.id;
        this.commandId = FitnessData.EnableAutomaticSpo.id;
        this.addToResponse = false;
    }

    @Override
    protected boolean requestSupported() {
        return !supportProvider.getDeviceState().hasCommandList() || supportProvider.getDeviceState().supportsSPo2();
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        boolean automaticSpoEnabled = supportProvider.getSetting(HuaweiSupportProvider.PREF_SPO2_AUTO, true);
        if (automaticSpoEnabled)
            LOG.info("Attempting to enable automatic SpO");
        else
            LOG.info("Attempting to disable automatic SpO");
        try {
            return new FitnessData.EnableAutomaticSpo.Request(paramsProvider, automaticSpoEnabled).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }
}
