/*  Copyright (C) 2024 Me7c7

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package br.dimensono.bandsono.huawei;

public class HuaweiDictTypes {
    public static final int SKIN_TEMPERATURE_CLASS = 400012;
    public static final int SKIN_TEMPERATURE_VALUE = 400012430;

    public static final int SLEEP_DETAILS_CLASS = 700013;

    /** Sequence dictionary (category 1, like 700013) with the arrhythmia PPG: requested as a sequence data file, kept raw. NOT a P2P class. */
    public static final int ARRHYTHMIA_SEQUENCE_DICT = 700004;
    /** P2P dictionary class (category 0) with the arrhythmia RESULT of a measurement. */
    public static final int ARRHYTHMIA_RESULT_CLASS = 500007;
}
