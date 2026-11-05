/*  Derived from Gadgetbridge (AGPL-3.0-or-later): the data classes and value ids come from
    Gadgetbridge's HuaweiDictionarySync* handlers; storage is done by the app. */
package br.dimensono.bandsono.huawei.p2p.dictionarysync;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.dimensono.bandsono.huawei.HuaweiState;
import br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService;

public interface HuaweiDictionarySyncInterface {
    /** Kind of value the app stores for this class (also the one used to resume the sync). */
    String getKind();

    int getDataClass();

    boolean supports(HuaweiState state);

    /** True for classes synced in the extra downloads (after the workouts) instead of the main dictionary stage. */
    default boolean isExtra() {
        return false;
    }

    /** Extract (timestamp ms, value) pairs from one batch. */
    List<double[]> parse(List<HuaweiP2PDataDictionarySyncService.DictData> dictData);

    /** Classes that carry several values per record override this to store one kind per value. */
    default Map<String, List<double[]>> parseByKind(List<HuaweiP2PDataDictionarySyncService.DictData> dictData) {
        Map<String, List<double[]>> out = new LinkedHashMap<>();
        out.put(getKind(), parse(dictData));
        return out;
    }
}
