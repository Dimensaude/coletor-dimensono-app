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

/** HRV (RMSSD, ms) recorded by the band, usually during sleep. */
public class HuaweiDictionarySyncHRV implements HuaweiDictionarySyncInterface {
    private final Logger LOG = LoggerFactory.getLogger(HuaweiDictionarySyncHRV.class);

    public static final int HRV_CLASS = 500044;
    public static final int HRV_RMSSD_VALUE = 500044831;

    @Override
    public String getKind() {
        return "hrv";
    }

    @Override
    public int getDataClass() {
        return HRV_CLASS;
    }

    @Override
    public boolean supports(HuaweiState state) {
        return state.supportsHRV();
    }

    @Override
    public List<double[]> parse(List<HuaweiP2PDataDictionarySyncService.DictData> dictData) {
        List<double[]> out = new ArrayList<>();
        for (HuaweiP2PDataDictionarySyncService.DictData dt : dictData) {
            Double rmssd = null;
            for (HuaweiP2PDataDictionarySyncService.DictData.DictDataValue val : dt.getData()) {
                if (val.getTag() != 10) {
                    LOG.info("HRV unexpected tag: {}", val.getTag());
                    continue;
                }
                if (val.getDataType() == HRV_RMSSD_VALUE) {
                    double value = HuaweiUtil.convBytes2Double(val.getValue());
                    if (value >= 0 && value <= 200)
                        rmssd = value;
                } else {
                    LOG.info("HRV unknown data type: {}", val.getDataType());
                }
            }
            if (rmssd != null)
                out.add(new double[]{dt.getStartTimestamp(), rmssd});
        }
        return out;
    }
}
