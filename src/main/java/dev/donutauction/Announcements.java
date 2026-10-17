package dev.donutauction;

import java.util.ArrayList;
import java.util.List;

/** Builds announcement text. Nothing here is ever sent; the player copies and sends it themselves. */
public final class Announcements {
	private Announcements() {}

	/** Minecraft chat accepts at most 256 characters per message. */
	private static final int MAX = 250;

	public static List<String> rules(Auction a, long now) {
		List<String> lines = new ArrayList<>();
		lines.add(fit("AUCTION: " + a.itemLabel() + " | Start " + Money.format(a.startBid)
			+ " | Min raise " + Money.format(a.minIncrement)
			+ " | Ends in " + Fmt.duration(Math.max(0, a.endMillis - now))));
		lines.add(fit("To bid: /pay " + a.host + " <amount>. Highest single payment when time ends wins. "
			+ (a.terms == null ? "" : a.terms)));
		return lines;
	}

	public static String winner(Auction a) {
		if (a.winner == null) return fit("Auction for " + a.itemLabel() + " ended with no valid bids.");
		return fit("AUCTION OVER: " + a.winner + " wins " + a.itemLabel() + " with " + Money.format(a.winningBid) + "!");
	}

	private static String fit(String s) {
		return s.length() <= MAX ? s : s.substring(0, MAX - 3) + "...";
	}
}
