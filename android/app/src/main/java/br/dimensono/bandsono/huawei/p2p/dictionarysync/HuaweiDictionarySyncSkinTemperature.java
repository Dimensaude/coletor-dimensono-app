/*  Derived from Gadgetbridge (AGPL-3.0-or-later): the data classes and value ids come from
    Gadgetbridge's HuaweiDictionarySync* handlers; storage is done by the app. */
package br.dimensono.bandsono.huawei.p2p.dictionarysync;

import java.util.ArrayList;
import java.util.List;

import br.dimensono.bandsono.huawei.HuaweiState;
import br.dimensono.bandsono.huawei.HuaweiUtil;
import br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService;
import br.dimensono.bandsono.log.Logger;
import br.dimensono.bandsono.log.LoggerFactory;

/** Wrist skin temperature (°C). */
public class HuaweiDictionarySyncSkinTemperature implements HuaweiDictionarySyncInterface {
    private final Logger LOG = LoggerFactory.getLogger(HuaweiDictionarySyncSkinTemperature.class);

    public static final int SKIN_TEMPERATURE_CLASS = 400012;
    public static final int SKIN_TEMPERATURE_VALUE = 400012430;

    @Override
    public String getKind() {
        return "temp";
    }

    @Override
    public int getDataClass() {
        return SKIN_TEMPERATURE_CLASS;
    }

    @Override
    public boolean supports(HuaweiState state) {
        return state.supportsTemperature();
    }

    @Override
    public List<double[]> parse(List<HuaweiP2PDataDictionarySyncService.DictData> dictData) {
        List<double[]> out = new ArrayList<>();
        for (HuaweiP2PDataDictionarySyncService.DictData dt : dictData) {
            Double temperature = null;
            for (HuaweiP2PDataDictionarySyncService.DictData.DictDataValue val : dt.getData()) {
                if (val.getTag() != 10)
                    continue;
                if (val.getDataType() == SKIN_TEMPERATURE_VALUE) {
                    double value = HuaweiUtil.convBytes2Double(val.getValue());
                    if (value >= 20 && value <= 42)
                        temperature = value;
                } else {
                    LOG.info("skin temperature unknown data type: {}", val.getDataType());
                }
            }
            if (temperature != null)
                out.add(new double[]{dt.getStartTimestamp(), temperature});
        }
        return out;
    }
}
