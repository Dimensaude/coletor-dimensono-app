package br.dimensono.bandsono.huawei;

/** Lightweight replacement of Gadgetbridge's GBDevice: just the fields the protocol fills in. */
public class BandDevice {
    private final String address;
    private final String name;
    private int batteryLevel = -1;
    private String firmwareVersion = "";
    private String hardwareVersion = "";
    private String model = "";

    public BandDevice(String address, String name) {
        this.address = address;
        this.name = name == null ? "" : name;
    }

    public String getAddress() { return address; }
    public String getName() { return name; }

    public int getBatteryLevel() { return batteryLevel; }
    public void setBatteryLevel(int level) { this.batteryLevel = level & 0xff; }
    public void setBatteryLevel(int level, int index) { if (index == 0) setBatteryLevel(level); }

    public String getFirmwareVersion() { return firmwareVersion; }
    public void setFirmwareVersion(String v) { this.firmwareVersion = v == null ? "" : v; }
    public String getHardwareVersion() { return hardwareVersion; }
    public void setFirmwareVersion2(String v) { this.hardwareVersion = v == null ? "" : v; }
    public String getModel() { return model; }
    public void setModel(String m) { this.model = m == null ? "" : m; }
}
