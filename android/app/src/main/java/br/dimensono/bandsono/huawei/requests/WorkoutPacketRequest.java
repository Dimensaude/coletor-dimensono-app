package br.dimensono.bandsono.huawei.requests;

import java.util.List;

import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;

/**
 * Sends one prepared workout packet (service 0x17) and keeps the band's answer, so the
 * workout sync in HuaweiSupportProvider can chain count → totals → data itself.
 */
public class WorkoutPacketRequest extends Request {
    private final HuaweiPacket packet;

    public WorkoutPacketRequest(HuaweiSupportProvider support, HuaweiPacket packet) {
        super(support);
        this.packet = packet;
        this.serviceId = packet.serviceId;
        this.commandId = packet.commandId;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return packet.serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    /** The parsed answer, or null when the band did not answer. */
    public HuaweiPacket getResponse() {
        return receivedPacket;
    }
}
