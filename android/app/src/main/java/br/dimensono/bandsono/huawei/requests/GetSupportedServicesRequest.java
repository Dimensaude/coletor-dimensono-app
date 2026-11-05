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
import br.dimensono.bandsono.huawei.packets.DeviceConfig;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;

public class GetSupportedServicesRequest extends Request {
    private static final Logger LOG = LoggerFactory.getLogger(GetSupportedServicesRequest.class);

    private final byte[] knownSupportedServices;

    public GetSupportedServicesRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.SupportedServices.id;
        this.knownSupportedServices = DeviceConfig.SupportedServices.knownSupportedServices;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new DeviceConfig.SupportedServices.Request(paramsProvider, this.knownSupportedServices).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        LOG.debug("handle Supported Services");

        if (!(receivedPacket instanceof DeviceConfig.SupportedServices.Response))
            throw new ResponseTypeMismatchException(receivedPacket, DeviceConfig.SupportedServices.Response.class);

        byte[] supportedServices = ((DeviceConfig.SupportedServices.Response) receivedPacket).supportedServices;
        List<Byte> activatedServices = new ArrayList<>();
        activatedServices.add((byte) 0x01); // device always support 1 service. add it
        for (int i = 0; i < supportedServices.length; i++) {
            if (supportedServices[i] == 1) {
                activatedServices.add(knownSupportedServices[i]);
            }
        }

        GetSupportedCommandsRequest supportedCommandsReq = new GetSupportedCommandsRequest(supportProvider, activatedServices);
        supportedCommandsReq.setupTimeoutUntilNext(5000);
        supportedCommandsReq.setFinalizeReq(new RequestCallback() {
                @Override
                public void timeout(Request request) {
                    LOG.warn("No answer from {}, continuing with what we know", request.getName());
                    supportProvider.initializeDynamicServices();
                }

                @Override
                public void handleException(Request request, ResponseParseException e) {
                    LOG.warn("Capability request failed, continuing", e);
                    supportProvider.initializeDynamicServices();
                }
            });
        nextRequest(supportedCommandsReq);
    }
}
