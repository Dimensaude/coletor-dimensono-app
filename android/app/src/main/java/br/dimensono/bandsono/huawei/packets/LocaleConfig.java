/*  Derived from Gadgetbridge (AGPL-3.0-or-later). */
package br.dimensono.bandsono.huawei.packets;

import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.HuaweiTLV;

/** Band language and measurement system (service 0x0c). */
public class LocaleConfig {
    public static final byte id = 0x0C;

    public static class SetLanguageSetting extends HuaweiPacket {
        public static final byte id = 0x01;

        public SetLanguageSetting(ParamsProvider paramsProvider, byte[] locale, byte measurement) {
            super(paramsProvider);

            this.serviceId = LocaleConfig.id;
            this.commandId = id;

            this.tlv = new HuaweiTLV()
                    .put(0x01, locale)
                    .put(0x02, measurement);

            this.complete = true;
        }
    }

    public static class MeasurementSystem {
        public static final byte metric = 0x00;
        public static final byte imperial = 0x01;
    }
}
