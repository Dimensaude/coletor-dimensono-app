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
import br.dimensono.bandsono.huawei.HuaweiUtil;
import br.dimensono.bandsono.huawei.packets.FitnessData;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;

public class SetActivityReminderRequest extends Request {
    private static final Logger LOG = LoggerFactory.getLogger(SetActivityReminderRequest.class);

    public SetActivityReminderRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = FitnessData.id;
        this.commandId = FitnessData.ActivityReminder.id;
    }

    @Override
    protected boolean requestSupported() {
        return supportProvider.getDeviceState().supportsActivityReminder();
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        boolean longsitSwitch = supportProvider.getSetting(HuaweiSupportProvider.PREF_INACTIVITY, true);
        String longsitInterval = "60";
        byte[] start = HuaweiUtil.timeToByte("08:00");
        byte[] end = HuaweiUtil.timeToByte("20:00");
        int cycle = 0x7f; // every day of the week

        try {
            return new FitnessData.ActivityReminder.Request(
                    paramsProvider,
                    longsitSwitch,
                    (byte) Integer.parseInt(longsitInterval),
                    start,
                    end,
                    (byte) cycle
            ).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() {
        LOG.debug("handle Set Activity Reminder");
    }
}