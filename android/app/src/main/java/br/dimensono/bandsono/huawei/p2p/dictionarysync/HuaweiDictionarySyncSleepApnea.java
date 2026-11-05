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

/** Breathing irregularity level the band computed for a night ("apnea" = level 1–4). */
public class HuaweiDictionarySyncSleepApnea implements HuaweiDictionarySyncInterface {
    private final Logger LOG = LoggerFactory.getLogger(HuaweiDictionarySyncSleepApnea.class);

    public static final int SLEEP_APNEA_CLASS = 500002;
    public static final int SLEEP_APNEA_LEVEL_VALUE = 500002847;

    @Override
    public String getKind() {
        return "apnea";
    }

    @Override
    public int getDataClass() {
        return SLEEP_APNEA_CLASS;
    }

    @Override
    public boolean supports(HuaweiState state) {
        return state.supportsSleepApnea();
    }

    @Override
    public List<double[]> parse(List<HuaweiP2PDataDictionarySyncService.DictData> dictData) {
        List<double[]> out = new ArrayList<>();
        for (HuaweiP2PDataDictionarySyncService.DictData dt : dictData) {
            for (HuaweiP2PDataDictionarySyncService.DictData.DictDataValue val : dt.getData()) {
                if (val.getTag() != 10) {
                    LOG.info("sleep apnea unsupported tag: {}", val.getTag());
                    continue;
                }
                if (val.getDataType() != SLEEP_APNEA_LEVEL_VALUE) {
                    LOG.info("sleep apnea unknown data type: {}", val.getDataType());
                    continue;
                }
                int level = (int) HuaweiUtil.convBytes2Double(val.getValue());
                if (level >= 1 && level <= 4)
                    out.add(new double[]{dt.getStartTimestamp(), level});
                else
                    LOG.info("sleep apnea invalid value: {}", level);
            }
        }
        return out;
    }
}
