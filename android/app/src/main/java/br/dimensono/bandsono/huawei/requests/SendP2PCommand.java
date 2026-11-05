/*  Copyright (C) 2024 Me7c7

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
import br.dimensono.bandsono.huawei.packets.P2P;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;

public class SendP2PCommand extends Request {

    private final byte cmdId;
    private final short sequenceId;
    private final String srcPackage;
    private final String dstPackage;
    private final String srcFingerprint;
    private final String dstFingerprint;
    private final byte[] sendData;
    private final int sendCode;

    public SendP2PCommand(HuaweiSupportProvider support,
                          byte cmdId,
                          short sequenceId,
                          String srcPackage,
                          String dstPackage,
                          String srcFingerprint,
                          String dstFingerprint,
                          byte[] sendData,
                          int sendCode) {
        super(support);
        this.serviceId = P2P.id;
        this.commandId = P2P.P2PCommand.id;

        this.cmdId = cmdId;
        this.sequenceId = sequenceId;
        this.srcPackage = srcPackage;
        this.dstPackage = dstPackage;
        this.srcFingerprint = srcFingerprint;
        this.dstFingerprint = dstFingerprint;
        this.sendData = sendData;
        this.sendCode = sendCode;
        this.addToResponse = false;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new P2P.P2PCommand.Request(paramsProvider, this.cmdId, this.sequenceId, this.srcPackage, this.dstPackage, this.srcFingerprint, this.dstFingerprint, this.sendData, this.sendCode).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected String getDualChannelDestPackage() {
        return this.dstPackage;
    }
}