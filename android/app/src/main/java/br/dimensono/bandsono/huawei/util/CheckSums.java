/*  Copyright (C) 2024 Gadgetbridge contributors (original code), 2026 BandSono adaptation

    This file is derived from Gadgetbridge and is free software: you can
    redistribute it and/or modify it under the terms of the GNU Affero General
    Public License as published by the Free Software Foundation, either
    version 3 of the License, or (at your option) any later version.

    It is distributed in the hope that it will be useful, but WITHOUT ANY
    WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
    FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for
    more details. <https://www.gnu.org/licenses/> */
package br.dimensono.bandsono.huawei.util;

public class CheckSums {
    public static int getCRC16(byte[] seq) {
        return getCRC16(seq, 0xFFFF);
    }

    public static int getCRC16(byte[] seq, int crc) {
        for (byte b : seq) {
            crc = ((crc >>> 8) | (crc << 8)) & 0xffff;
            crc ^= (b & 0xff);
            crc ^= ((crc & 0xff) >> 4);
            crc ^= (crc << 12) & 0xffff;
            crc ^= ((crc & 0xFF) << 5) & 0xffff;
        }
        crc &= 0xffff;
        return crc;
    }
}
