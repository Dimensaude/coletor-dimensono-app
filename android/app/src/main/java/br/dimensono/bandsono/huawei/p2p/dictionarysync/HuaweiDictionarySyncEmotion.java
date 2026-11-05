/*  Derived from Gadgetbridge (AGPL-3.0-or-later): the data classes and value ids come from
    Gadgetbridge's HuaweiDictionarySync* handlers; storage is done by the app. */
package br.dimensono.bandsono.huawei.p2p.dictionarysync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.dimensono.bandsono.huawei.HuaweiState;
import br.dimensono.bandsono.huawei.HuaweiUtil;
import br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService;
import br.dimensono.bandsono.log.Logger;
import br.dimensono.bandsono.log.LoggerFactory;

/**
 * Emotional state estimated by the band ("emotion" = status 0–99, plus valence and arousal
 * 0–100 and the uncorrected status), stored as one dictionary kind per value.
 */
public class HuaweiDictionarySyncEmotion implements HuaweiDictionarySyncInterface {
    private final Logger LOG = LoggerFactory.getLogger(HuaweiDictionarySyncEmotion.class);

    public static final int EMOTION_CLASS = 500031;
    public static final int EMOTION_STATUS_VALUE = 500031195;
    public static final int EMOTION_VALENCE_CHARACTER_VALUE = 500031347;
    public static final int EMOTION_ORIGIN_STATUS_VALUE = 500031160;
    public static final int EMOTION_AROUSAL_CHARACTER_VALUE = 500031842;

    public static final String KIND_STATUS = "emotion";
    public static final String KIND_VALENCE = "emotion_valence";
    public static final String KIND_AROUSAL = "emotion_arousal";
    public static final String KIND_ORIGIN = "emotion_origin";

    @Override
    public String getKind() {
        return KIND_STATUS;
    }

    @Override
    public int getDataClass() {
        return EMOTION_CLASS;
    }

    @Override
    public boolean supports(HuaweiState state) {
        return state.supportsEmotion();
    }

    @Override
    public List<double[]> parse(List<HuaweiP2PDataDictionarySyncService.DictData> dictData) {
        return parseByKind(dictData).get(KIND_STATUS);
    }

    @Override
    public Map<String, List<double[]>> parseByKind(List<HuaweiP2PDataDictionarySyncService.DictData> dictData) {
        Map<String, List<double[]>> out = new LinkedHashMap<>();
        out.put(KIND_STATUS, new ArrayList<>());
        out.put(KIND_VALENCE, new ArrayList<>());
        out.put(KIND_AROUSAL, new ArrayList<>());
        out.put(KIND_ORIGIN, new ArrayList<>());
        for (HuaweiP2PDataDictionarySyncService.DictData dt : dictData) {
            double ts = dt.getStartTimestamp();
            for (HuaweiP2PDataDictionarySyncService.DictData.DictDataValue val : dt.getData()) {
                if (val.getTag() != 10) {
                    LOG.info("emotion unexpected tag: {}", val.getTag());
                    continue;
                }
                double value = HuaweiUtil.convBytes2Double(val.getValue());
                if (val.getDataType() == EMOTION_STATUS_VALUE) {
                    if (value >= 0 && value < 100) out.get(KIND_STATUS).add(new double[]{ts, value});
                } else if (val.getDataType() == EMOTION_VALENCE_CHARACTER_VALUE) {
                    if (value >= 0 && value <= 100) out.get(KIND_VALENCE).add(new double[]{ts, value});
                } else if (val.getDataType() == EMOTION_AROUSAL_CHARACTER_VALUE) {
                    if (value >= 0 && value <= 100) out.get(KIND_AROUSAL).add(new double[]{ts, value});
                } else if (val.getDataType() == EMOTION_ORIGIN_STATUS_VALUE) {
                    if (value >= 0 && value < 100) out.get(KIND_ORIGIN).add(new double[]{ts, value});
                } else {
                    LOG.info("emotion unknown data type: {}", val.getDataType());
                }
            }
        }
        return out;
    }
}
