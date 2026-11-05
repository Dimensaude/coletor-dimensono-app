/*  Derived from Gadgetbridge (AGPL-3.0-or-later). */
package br.dimensono.bandsono.huawei.packets;

import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.HuaweiTLV;

/** Account the band is bound to (service 0x1a). The Coletor has no Huawei account: it sends an empty one, like Gadgetbridge. */
public class AccountRelated {
    public static final byte id = 0x1A;

    public static class SendExtendedAccountToDevice {
        public static final byte id = 0x05;

        public static class Request extends HuaweiPacket {
            public Request(ParamsProvider paramsProvider, boolean accountPairingOptimization, String account) {
                super(paramsProvider);

                this.serviceId = AccountRelated.id;
                this.commandId = id;

                this.tlv = new HuaweiTLV();
                if (!account.isEmpty())
                    tlv.put(0x01, account);
                else
                    tlv.put(0x01, (byte) 0x00);
                if (accountPairingOptimization)
                    this.tlv.put(0x03, (byte) 0x01);
                this.complete = true;
            }
        }
    }
}
