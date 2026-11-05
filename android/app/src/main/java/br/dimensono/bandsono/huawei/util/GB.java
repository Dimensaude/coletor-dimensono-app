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

/** Minimal replacement of Gadgetbridge's GB helper (only what the protocol code needs). */
public class GB {
    private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

    public static String hexdump(byte[] buffer, int offset, int length) {
        if (buffer == null) return "null";
        if (length == -1) {
            length = buffer.length - offset;
        }
        char[] hexChars = new char[length * 2];
        for (int i = 0; i < length; i++) {
            int v = buffer[i + offset] & 0xFF;
            hexChars[i * 2] = HEX_CHARS[v >>> 4];
            hexChars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static String hexdump(byte[] buffer) {
        if (buffer == null) return "null";
        return hexdump(buffer, 0, buffer.length);
    }

    /** Hex dump computed only when the log line is actually built. */
    public static Object lazyHexdump(final byte[] buffer) {
        return new Object() {
            @Override
            public String toString() {
                return hexdump(buffer);
            }
        };
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
}
