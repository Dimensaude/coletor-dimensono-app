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

public class GetProductInformationRequest extends Request {
    private static final Logger LOG = LoggerFactory.getLogger(GetProductInformationRequest.class);

    public GetProductInformationRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.ProductInfo.id;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new DeviceConfig.ProductInfo.Request(paramsProvider, supportProvider.getHuaweiType()).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        LOG.debug("handle Product Information");

        if (!(receivedPacket instanceof DeviceConfig.ProductInfo.Response))
            throw new ResponseTypeMismatchException(receivedPacket, DeviceConfig.ProductInfo.Response.class);

        getDevice().setFirmwareVersion(((DeviceConfig.ProductInfo.Response) receivedPacket).softwareVersion);
        getDevice().setFirmwareVersion2(((DeviceConfig.ProductInfo.Response) receivedPacket).hardwareVersion);
        getDevice().setModel(((DeviceConfig.ProductInfo.Response) receivedPacket).productModel);

        supportProvider.getDeviceState().setOtaSoftwareVersion(((DeviceConfig.ProductInfo.Response) receivedPacket).softwareVersion);
        supportProvider.getDeviceState().setOtaSignatureLength(((DeviceConfig.ProductInfo.Response) receivedPacket).otaSignatureLength);
   }
}
