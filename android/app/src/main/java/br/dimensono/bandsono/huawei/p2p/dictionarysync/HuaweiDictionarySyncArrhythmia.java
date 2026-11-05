/*  Derived from Gadgetbridge (AGPL-3.0-or-later): the data classes and value ids come from
    Gadgetbridge's HuaweiDictionarySync* handlers; storage is done by the app. */
package br.dimensono.bandsono.huawei.p2p.dictionarysync;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.dimensono.bandsono.huawei.HuaweiDictTypes;
import br.dimensono.bandsono.huawei.HuaweiState;
import br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService;
import br.dimensono.bandsono.log.Logger;
import br.dimensono.bandsono.log.LoggerFactory;

/**
 * Result records of the band's arrhythmia measurement (P2P class 500007). Collection only: the raw numbers are stored
 * as the band reports them (no range filter, no reading of what they mean) and nothing is shown. One dictionary kind per
 * value: the measure type and the PPG irregular-heartbeat code. Runs in the extra downloads, not in the main dictionary
 * stage, so a band that does not answer it cannot hold back HRV, temperature, emotion or apnea.
 */
public class HuaweiDictionarySyncArrhythmia implements HuaweiDictionarySyncInterface {
    private final Logger LOG = LoggerFactory.getLogger(HuaweiDictionarySyncArrhythmia.class);

    public static final int RESULT_SAVE_TIME = 500007278;
    public static final int RESULT_MEASURE_TYPE = 500007088;
    public static final int RESULT_PPG_IRREGULAR_HEARTBEAT = 2200;

    public static final String KIND_MEASURE_TYPE = "arrhythmia_result_measure_type";
    public static final String KIND_PPG = "arrhythmia_result_ppg";

    @Override
    public String getKind() {
        return KIND_MEASURE_TYPE;
    }

    @Override
    public int getDataClass() {
        return HuaweiDictTypes.ARRHYTHMIA_RESULT_CLASS;
    }

    @Override
    public boolean supports(HuaweiState state) {
        return state.supportsArrhythmia();
    }

    @Override
    public boolean isExtra() {
        return true;
    }

    @Override
    public List<double[]> parse(List<HuaweiP2PDataDictionarySyncService.DictData> dictData) {
        return parseByKind(dictData).get(KIND_MEASURE_TYPE);
    }

    /** A value of 8 bytes: tag 10 carries a double, tag 11 a long; anything else is not understood (NaN). */
    static double number(HuaweiP2PDataDictionarySyncService.DictData.DictDataValue val) {
        byte[] v = val.getValue();
        if (v == null || v.length != 8) return Double.NaN;
        ByteBuffer b = ByteBuffer.wrap(v);
        return val.getTag() == 11 ? (double) b.getLong() : b.getDouble();
    }

    @Override
    public Map<String, List<double[]>> parseByKind(List<HuaweiP2PDataDictionarySyncService.DictData> dictData) {
        Map<String, List<double[]>> out = new LinkedHashMap<>();
        out.put(KIND_MEASURE_TYPE, new ArrayList<>());
        out.put(KIND_PPG, new ArrayList<>());
        for (HuaweiP2PDataDictionarySyncService.DictData dt : dictData) {
            double ts = dt.getStartTimestamp();
            for (HuaweiP2PDataDictionarySyncService.DictData.DictDataValue val : dt.getData()) {
                double value = number(val);
                if (Double.isNaN(value)) {
                    LOG.info("arrhythmia value type {} not understood", val.getDataType());
                    continue;
                }
                if (val.getDataType() == RESULT_SAVE_TIME) {
                    continue; // only a validity marker of the record
                } else if (val.getDataType() == RESULT_MEASURE_TYPE) {
                    out.get(KIND_MEASURE_TYPE).add(new double[]{ts, value}); // whatever the band sent: nothing is interpreted here
                } else if (val.getDataType() == RESULT_PPG_IRREGULAR_HEARTBEAT) {
                    out.get(KIND_PPG).add(new double[]{ts, value});
                } else {
                    LOG.info("arrhythmia unknown data type: {}", val.getDataType());
                }
            }
        }
        return out;
    }
}
