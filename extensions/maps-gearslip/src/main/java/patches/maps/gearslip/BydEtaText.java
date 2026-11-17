package patches.maps.gearslip;

import java.util.Locale;

/**
 * Decides whether the navigation card's status line may carry the vehicle's state of charge and
 * remaining range, and how that suffix reads. Deliberately free of Android types so the rules are
 * unit-testable on the JVM.
 */
public final class BydEtaText {
    private static final String SEPARATOR = " · ";
    private static final String[] ETA_TOKENS = {"min", "km", "mi", "hr"};

    private BydEtaText() {
    }

    /** True when {@code text} reads like a navigation status ("12 min · 5.2 km") rather than a message. */
    public static boolean looksLikeEta(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        boolean digit = false;
        for (int i = 0; i < text.length() && !digit; i++) {
            digit = Character.isDigit(text.charAt(i));
        }
        if (!digit) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String token : ETA_TOKENS) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** "62% · 310 km", "310 km", "62%", or "" when neither value is known. */
    public static String suffix(int soc, int rangeKm, String unit) {
        StringBuilder out = new StringBuilder();
        if (soc >= 0) {
            out.append(soc).append('%');
        }
        if (rangeKm >= 0) {
            if (out.length() > 0) {
                out.append(SEPARATOR);
            }
            out.append(rangeKm).append(' ').append(unit == null || unit.isEmpty() ? "km" : unit);
        }
        return out.toString();
    }

    /** The original status text with the vehicle suffix appended, or the original untouched. */
    public static String decorate(String original, int soc, int rangeKm, String unit) {
        if (original == null || original.isEmpty() || !looksLikeEta(original)) {
            return original;
        }
        String suffix = suffix(soc, rangeKm, unit);
        return suffix.isEmpty() ? original : original + SEPARATOR + suffix;
    }

    /** Fixed values used by the anchor-verification build only. */
    public static String decorateMarker(String original) {
        return decorate(original, 62, 310, "km");
    }
}
