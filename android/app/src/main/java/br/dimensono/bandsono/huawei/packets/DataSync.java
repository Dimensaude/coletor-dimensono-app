/*  Derived from Gadgetbridge (AGPL-3.0-or-later). */
package br.dimensono.bandsono.huawei.packets;

import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.HuaweiTLV;

/** "United device" data sync (service 0x37): configuration messages between band apps and phone apps. */
public class DataSync {
    public static final byte id = 0x37;

    public static class ConfigCommand {
        public static final byte id = 0x01;

        /** One configuration item (id, action, data), like Gadgetbridge's HuaweiDataSyncCommon.ConfigData. */
        public static class Request extends HuaweiPacket {
            public Request(ParamsProvider paramsProvider, String srcPackage, String dstPackage,
                           int configId, byte configAction, byte[] configData) {
                super(paramsProvider);

                this.serviceId = DataSync.id;
                this.commandId = id;

                HuaweiTLV item = new HuaweiTLV()
                        .put(0x05, configId)
                        .put(0x06, configAction);
                if (configData != null)
                    item.put(0x07, configData);
                this.tlv = new HuaweiTLV()
                        .put(0x01, srcPackage)
                        .put(0x02, dstPackage)
                        .put(0x83, new HuaweiTLV().put(0x84, item));
                this.complete = true;
            }
        }
    }

    /** Turns the band's emotion monitoring on or off (Gadgetbridge HuaweiDataSyncEmotion). */
    public static Request emotionState(HuaweiPacket.ParamsProvider p, boolean on) {
        return new Request(p, on);
    }

    public static class Request extends ConfigCommand.Request {
        Request(HuaweiPacket.ParamsProvider p, boolean on) {
            super(p, "hw.health.emotion", "hw.watch.health.emotion", 900500028, (byte) 1,
                    new HuaweiTLV().put(0x01, (byte) (on ? 1 : 0)).serialize());
        }
    }
}
