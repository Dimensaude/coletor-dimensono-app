package br.dimensono.bandsono;

import java.util.Locale;
import java.util.regex.Pattern;

/** Participant code (the patient's phone from the server, or a legacy code) and the professional code. */
public final class ParticipantCode {
    private static final Pattern PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9-]{3,39}$");
    private static final Pattern PROFESSIONAL = Pattern.compile("^DMS-[A-Z0-9]{8,16}$");

    private ParticipantCode() {
    }

    /**
     * Trim + upper case; null when the text is not a valid code. A professional code (DMS-...) is never a
     * participant code: it would merge the patients of one professional into a single participant.
     */
    public static String normalize(String raw) {
        if (raw == null)
            return null;
        String code = raw.trim().toUpperCase(Locale.ROOT);
        if (code.startsWith("DMS-"))
            return null;
        return PATTERN.matcher(code).matches() ? code : null;
    }

    /** Trim + upper case; null when the text is not a professional code (DMS- plus 8 to 16 letters/digits). */
    public static String normalizeProfessional(String raw) {
        if (raw == null)
            return null;
        String code = raw.trim().toUpperCase(Locale.ROOT);
        return PROFESSIONAL.matcher(code).matches() ? code : null;
    }
}
