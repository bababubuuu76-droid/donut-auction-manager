package dev.donutauction;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * User-editable config. The only built-in format comes from a real message supplied by the player
 * ("<name> paid you $ <amount>"). Add others only from real messages recorded with /aucmod capture on.
 */
public class FormatConfig {
	public static final Set<String> KINDS = Set.of("INCOMING_CONFIRMED", "OUTGOING_CONFIRMED", "FAILED");
	public static final Set<String> SOURCES = Set.of("system", "actionbar");
	public static final Set<String> TARGETS = Set.of("plain", "styled");

	public String _help = "formats: each entry = {id, kind, source, target, regex}. kind: INCOMING_CONFIRMED | OUTGOING_CONFIRMED | FAILED. "
		+ "source: system (chat-area server message) | actionbar. target: plain (text) | styled (text with <color>..</> tags, see capture.log). "
		+ "The regex must match the WHOLE message (it is anchored) and INCOMING_CONFIRMED needs named groups (?<payer>..) and (?<amount>..). "
		+ "Only add formats copied from real messages in capture.log.";
	public String terms = "All payments are final and non-refundable, including payments from outbid players.";
	public int dedupeWindowMs = 750;
	/** Server messages containing any of these but matching no format go to the manual review queue. */
	public List<String> reviewKeywords = new ArrayList<>(List.of("$"));
	public List<Format> formats = new ArrayList<>(List.of(defaultIncoming()));

	/** Built from a real DonutSMP message supplied by the player: "<name> paid you $ <amount>". */
	private static Format defaultIncoming() {
		Format f = new Format();
		f.id = "incoming-paid-you";
		f.kind = "INCOMING_CONFIRMED";
		f.source = "system";
		f.target = "plain";
		f.regex = "(?<payer>[A-Za-z0-9_.]{1,17}) paid you \\$\\s?(?<amount>[0-9][0-9.,]*[kKmMbBtT]?)";
		return f;
	}

	public static class Format {
		public String id;
		public String kind;
		public String source = "system";
		public String target = "plain";
		public String regex;
		public transient Pattern pattern;
	}

	/** Compiles regexes. @return human-readable problems (empty if everything is fine). */
	public List<String> compile() {
		List<String> problems = new ArrayList<>();
		if (formats == null) formats = new ArrayList<>();
		if (reviewKeywords == null) reviewKeywords = new ArrayList<>();
		for (Format f : formats) {
			String name = f.id == null ? "(no id)" : f.id;
			f.pattern = null;
			if (f.kind == null || !KINDS.contains(f.kind)) { problems.add(name + ": kind must be one of " + KINDS); continue; }
			if (!SOURCES.contains(f.source)) { problems.add(name + ": source must be system or actionbar"); continue; }
			if (!TARGETS.contains(f.target)) { problems.add(name + ": target must be plain or styled"); continue; }
			if (f.regex == null || f.regex.isBlank()) { problems.add(name + ": regex is empty"); continue; }
			try {
				f.pattern = Pattern.compile(f.regex);
			} catch (PatternSyntaxException e) {
				problems.add(name + ": bad regex (" + e.getDescription() + ")");
				continue;
			}
			if (f.kind.equals("INCOMING_CONFIRMED")
				&& (!f.regex.contains("(?<payer>") || !f.regex.contains("(?<amount>"))) {
				problems.add(name + ": INCOMING_CONFIRMED needs named groups (?<payer>..) and (?<amount>..)");
				f.pattern = null;
			}
		}
		return problems;
	}
}
