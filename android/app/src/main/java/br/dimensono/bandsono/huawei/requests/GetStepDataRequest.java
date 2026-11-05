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

import java.util.ArrayList;
import java.util.List;

import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.packets.FitnessData;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;

public class GetStepDataRequest extends Request {
    private static final Logger LOG = LoggerFactory.getLogger(GetStepDataRequest.class);

    short maxCount;
    short count;

    public GetStepDataRequest(HuaweiSupportProvider support, short maxCount, short count) {
        super(support);
        this.serviceId = FitnessData.id;
        this.commandId = FitnessData.MessageData.stepId;
        this.maxCount = maxCount;
        this.count = count;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new FitnessData.MessageData.Request(paramsProvider, this.commandId, this.count).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        if (!(receivedPacket instanceof FitnessData.MessageData.StepResponse))
            throw new ResponseTypeMismatchException(receivedPacket, FitnessData.MessageData.StepResponse.class);

        FitnessData.MessageData.StepResponse response = (FitnessData.MessageData.StepResponse) receivedPacket;

        if (response.number != this.count) {
            LOG.warn("Counts do not match! Received: {}, expected: {}", response.number, this.count);
            this.count = response.number; // This stops it from going into a loop
        }

        for (FitnessData.MessageData.StepResponse.SubContainer subContainer : response.containers) {
            java.util.List<int[]> unknown = new java.util.ArrayList<>();
            if (subContainer.unknownTVs != null)
                for (FitnessData.MessageData.StepResponse.SubContainer.TV tv : subContainer.unknownTVs)
                    unknown.add(new int[]{tv.bitmap, tv.tag, tv.value});
            this.supportProvider.noteStepRecord(subContainer.timestamp, subContainer.data, unknown);
            if (subContainer.parsedData != null) {
                int steps = subContainer.steps == -1 ? 0 : subContainer.steps;
                int calories = subContainer.calories == -1 ? 0 : subContainer.calories;
                int distance = subContainer.distance == -1 ? 0 : subContainer.distance;
                this.supportProvider.addStepData(subContainer.timestamp, steps, calories, distance,
                        subContainer.spo, subContainer.heartrate, subContainer.restingHeartRate);
            } else {
                LOG.error(subContainer.parsedDataError);
            }
        }

        if (count + 1 < maxCount) {
            GetStepDataRequest nextRequest = new GetStepDataRequest(supportProvider, this.maxCount, (short) (this.count + 1));
            nextRequest.setFinalizeReq(this.finalizeReq);
            nextRequest.setupTimeoutUntilNext(10000);
            this.nextRequest(nextRequest);
        }
    }
}
