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

/* In order to be compatible with all devices, request send all possible commands
to all possible services. This implies long packet which is not handled on the device.
Thus, this request could be sliced in 3 packets. But this command does not support slicing.
Thus, one need to send multiple requests and concat the response.
Packets should be 240 bytes max */
package br.dimensono.bandsono.huawei.requests;

import br.dimensono.bandsono.log.Logger;
import br.dimensono.bandsono.log.LoggerFactory;

import java.util.List;
import java.util.Map;

import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.packets.DeviceConfig;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;

public class GetSupportedCommandsRequest extends Request {
    private static final Logger LOG = LoggerFactory.getLogger(GetSupportedCommandsRequest.class);

    private final Map<Integer, byte[]> commandsPerService;
    private final List<Byte> activatedServices;

    public GetSupportedCommandsRequest(
            HuaweiSupportProvider support,
            List<Byte> activatedServices
    ) {
        super(support);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.SupportedCommands.id;
        this.commandsPerService = DeviceConfig.SupportedCommands.commandsPerService;
        this.activatedServices = activatedServices;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        DeviceConfig.SupportedCommands.Request commandsRequest = new DeviceConfig.SupportedCommands.Request(paramsProvider);
        byte nextService = activatedServices.remove(0);
        boolean fits = commandsRequest.addCommandsForService(nextService, this.commandsPerService.get((int) nextService));
        while (fits && !activatedServices.isEmpty()) {
            nextService = activatedServices.remove(0);
            fits = commandsRequest.addCommandsForService(nextService, this.commandsPerService.get((int) nextService));
        }
        if (!fits)
            activatedServices.add(0, nextService); // Put the extra back
        try {
            return commandsRequest.serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    RequestCallback dynamicServicesReq = new RequestCallback() {
        @Override
        public void call() {
            supportProvider.initializeDynamicServices();
        }
    };

    @Override
    protected void processResponse() throws ResponseParseException {
        LOG.debug("handle Supported Commands");

        if (!(receivedPacket instanceof DeviceConfig.SupportedCommands.Response))
            throw new ResponseTypeMismatchException(receivedPacket, DeviceConfig.SupportedCommands.Response.class);

        for (DeviceConfig.SupportedCommands.Response.CommandsList commandsList : ((DeviceConfig.SupportedCommands.Response) receivedPacket).commandsLists) {
            supportProvider.getDeviceState().addCommandsForService(
                    commandsList.service,
                    commandsList.commands
            );
        }

        if (!activatedServices.isEmpty()) {
            GetSupportedCommandsRequest nextRequest = new GetSupportedCommandsRequest(supportProvider, activatedServices);
            nextRequest.setupTimeoutUntilNext(5000);
            nextRequest.setFinalizeReq(this.finalizeReq);
            this.nextRequest(nextRequest);
        } else {
            supportProvider.getDeviceState().printCommandsPerService();
            if(supportProvider.getDeviceState().supportsExpandCapability()) {
                GetExpandCapabilityRequest nextRequest = new GetExpandCapabilityRequest(supportProvider);
                nextRequest.setupTimeoutUntilNext(3000);
                nextRequest.setFinalizeReq(new RequestCallback() {
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
                this.nextRequest(nextRequest);
            } else {
                dynamicServicesReq.call();
            }
        }
    }
}
