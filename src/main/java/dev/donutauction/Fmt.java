package dev.donutauction;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Duration parsing and formatting helpers. */
public final class Fmt {
	private Fmt() {}

	public static final long MAX_DURATION_MS = 7L * 24 * 3600 * 1000;
	private static final Pattern WHOLE = Pattern.compile("^(?:\\d+[dhms]?)+$");
	private static final Pattern PART = Pattern.compile("(\\d+)([dhms]?)");

	/** "90" = 90 seconds; "10m", "1h30m", "2d". @return millis, or -1 if invalid / zero / too long. */
	public static long parseDuration(String input) {
		if (input == null) return -1;
		String s = input.trim().toLowerCase(Locale.ROOT);
		if (!WHOLE.matcher(s).matches()) return -1;
		Matcher m = PART.matcher(s);
		long total = 0;
		while (m.find()) {
			long n;
			try { n = Long.parseLong(m.group(1)); } catch (NumberFormatException e) { return -1; }
			long unit = switch (m.group(2)) {
				case "d" -> 86_400_000L;
				case "h" -> 3_600_000L;
				case "m" -> 60_000L;
				default -> 1_000L;
			};
			total += n * unit;
			if (total > MAX_DURATION_MS) return -1;
		}
		return total > 0 ? total : -1;
	}

	public static String duration(long ms) {
		long s = Math.max(0, (ms + 999) / 1000);
		long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
		if (h > 0) return h + "h " + pad(m) + "m " + pad(sec) + "s";
		if (m > 0) return m + "m " + pad(sec) + "s";
		return sec + "s";
	}

	private static String pad(long v) {
		return v < 10 ? "0" + v : Long.toString(v);
	}
}
