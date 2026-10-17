package dev.donutauction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Parses and formats amounts such as 1500, 1,500, 2.5k, 1.5m, 3b, 1t. */
public final class Money {
	private Money() {}

	/** @return whole-dollar amount, or -1 if it can't be parsed. */
	public static long parse(String token) {
		if (token == null) return -1;
		String t = token.trim().replace("$", "").replace(",", "").replace(" ", "");
		if (t.isEmpty()) return -1;
		char last = t.charAt(t.length() - 1);
		BigDecimal mult = BigDecimal.ONE;
		if (Character.isLetter(last)) {
			switch (Character.toLowerCase(last)) {
				case 'k' -> mult = BigDecimal.valueOf(1_000L);
				case 'm' -> mult = BigDecimal.valueOf(1_000_000L);
				case 'b' -> mult = BigDecimal.valueOf(1_000_000_000L);
				case 't' -> mult = BigDecimal.valueOf(1_000_000_000_000L);
				default -> { return -1; }
			}
			t = t.substring(0, t.length() - 1);
		}
		try {
			return new BigDecimal(t).multiply(mult).setScale(0, RoundingMode.DOWN).longValueExact();
		} catch (RuntimeException e) {
			return -1;
		}
	}

	public static String format(long amount) {
		if (amount >= 1_000_000_000_000L) return "$" + trim(amount / 1e12) + "T";
		if (amount >= 1_000_000_000L) return "$" + trim(amount / 1e9) + "B";
		if (amount >= 1_000_000L) return "$" + trim(amount / 1e6) + "M";
		if (amount >= 1_000L) return "$" + trim(amount / 1e3) + "K";
		return "$" + amount;
	}

	private static String trim(double v) {
		String s = String.format(Locale.ROOT, "%.2f", v);
		return s.replaceAll("0+$", "").replaceAll("\\.$", "");
	}
}
