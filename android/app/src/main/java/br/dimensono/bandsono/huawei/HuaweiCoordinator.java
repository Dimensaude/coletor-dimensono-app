/*  Derived from Gadgetbridge's HuaweiCoordinator (AGPL-3.0-or-later).
    Only the parts the Band 10 protocol path needs. */
package br.dimensono.bandsono.huawei;

public class HuaweiCoordinator {
    public enum HuaweiDeviceType {
        AW(0),     //BLE behind
        BR(1),
        BLE(2),
        SMART(5)   //BLE behind
        ;

        final int huaweiType;

        HuaweiDeviceType(int huaweiType) {
            this.huaweiType = huaweiType;
        }

        public int getType() {
            return huaweiType;
        }
    }

    /** Honor Watch 5 PAKE/STS authentication: not used by Huawei bands. */
    public boolean supportsHiChainPake() {
        return false;
    }

    /** Honor's alternative GATT service: not used by Huawei bands. */
    public boolean isNewHonorProtocol() {
        return false;
    }
}
