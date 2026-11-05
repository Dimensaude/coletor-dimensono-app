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

public class StringUtils {
    public static boolean isNullOrEmpty(CharSequence string) {
        return string == null || string.length() == 0;
    }

    public static String bytesToHex(byte[] array) {
        if (array == null) return "null";
        return GB.hexdump(array, 0, -1);
    }

    public static byte[] hexToBytes(String hexString) {
        if ((hexString.length() % 2) == 1) {
            hexString = "0" + hexString;
        }
        byte[] bytes = new byte[hexString.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            String slice = hexString.substring(i * 2, i * 2 + 2);
            bytes[i] = (byte) Integer.parseInt(slice, 16);
        }
        return bytes;
    }
}
