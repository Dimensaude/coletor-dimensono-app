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
import br.dimensono.bandsono.huawei.packets.DeviceConfig;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;
import br.dimensono.bandsono.huawei.util.GB;

public class GetBatteryLevelRequest extends Request {
    private static final Logger LOG = LoggerFactory.getLogger(GetBatteryLevelRequest.class);

    public GetBatteryLevelRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.BatteryLevel.id;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new DeviceConfig.BatteryLevel.Request(paramsProvider).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        LOG.debug("handle Battery Level");
        if (!(receivedPacket instanceof DeviceConfig.BatteryLevel.Response))
            throw new ResponseTypeMismatchException(receivedPacket, DeviceConfig.BatteryLevel.Response.class);

        DeviceConfig.BatteryLevel.Response response = (DeviceConfig.BatteryLevel.Response) receivedPacket;
        if (response.multi_level == null) {
            supportProvider.onBatteryLevel(response.level & 0xff);
        } else if (response.multi_level.length > 0) {
            supportProvider.onBatteryLevel(response.multi_level[0] & 0xff);
        }
    }
}
