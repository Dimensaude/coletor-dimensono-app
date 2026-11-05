/*  Copyright (C) 2025 Me7c7

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
import br.dimensono.bandsono.huawei.HuaweiStressParser;
import br.dimensono.bandsono.huawei.packets.Stress;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;

public class SetStressRequest extends Request {
    private static final Logger LOG = LoggerFactory.getLogger(SetStressRequest.class);

    private final boolean automaticStressEnabled;
    public SetStressRequest(HuaweiSupportProvider support, boolean automaticStressEnabled) {
        super(support);
        this.serviceId = Stress.id;
        this.commandId = Stress.AutomaticStress.id;
        this.addToResponse = false;
        this.automaticStressEnabled = automaticStressEnabled;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        byte status;
        List<Float> features = null;
        byte score = 0;
        long time = 0;
        if (automaticStressEnabled) {
            status = 1;
            HuaweiStressParser.StressData stressData = supportProvider.getLastStressData();
            if(stressData == null || stressData.score == 0 || stressData.endTime == 0) {
                throw new RequestCreationException("No data for activate");
            }
            features = stressData.features;
            score = stressData.score;
            time = stressData.endTime;
            LOG.info("Attempting to enable automatic stress");
        } else {
            LOG.info("Attempting to disable automatic stress");
            status = 2;
        }

        try {
            return new Stress.AutomaticStress.Request(paramsProvider, status, score, features, (int) (time / 1000)).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }
}
