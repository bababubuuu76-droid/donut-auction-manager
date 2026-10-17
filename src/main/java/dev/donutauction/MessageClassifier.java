package dev.donutauction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides what an incoming message is. Only server system messages / action bar text matching a
 * user-supplied format can ever become a confirmed payment. Player chat never can.
 */
public class MessageClassifier {
	public enum Channel { SYSTEM, ACTIONBAR, CHAT }

	public enum Kind { INCOMING_CONFIRMED, OUTGOING_CONFIRMED, FAILED, NEEDS_REVIEW, UNRELATED }

	public static final class Result {
		public final Kind kind;
		public final String payer;
		public final long amount;
		public final String reason;

		Result(Kind kind, String payer, long amount, String reason) {
			this.kind = kind;
			this.payer = payer;
			this.amount = amount;
			this.reason = reason;
		}
	}

	private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_.]{1,17}$");

	private final FormatConfig cfg;

	public MessageClassifier(FormatConfig cfg) {
		this.cfg = cfg;
	}

	public Result classify(Channel channel, String plain, String styled) {
		if (channel == Channel.CHAT) {
			return new Result(Kind.UNRELATED, null, 0, "player chat is never a payment confirmation");
		}
		String source = channel == Channel.SYSTEM ? "system" : "actionbar";
		List<Result> hits = new ArrayList<>();
		for (FormatConfig.Format f : cfg.formats) {
			if (f.pattern == null || !source.equals(f.source)) continue;
			String text = "styled".equals(f.target) ? styled : plain;
			Matcher m = f.pattern.matcher(text);
			if (!m.matches()) continue;
			hits.add(build(f, m));
		}
		if (hits.isEmpty()) {
			String low = plain.toLowerCase(Locale.ROOT);
			for (String k : cfg.reviewKeywords) {
				if (k != null && !k.isEmpty() && low.contains(k.toLowerCase(Locale.ROOT))) {
					return new Result(Kind.NEEDS_REVIEW, null, 0,
						"contains '" + k + "' but matches no verified format");
				}
			}
			return new Result(Kind.UNRELATED, null, 0, "no format matched");
		}
		Result first = hits.get(0);
		for (Result r : hits) {
			boolean same = r.kind == first.kind && r.amount == first.amount
				&& (r.payer == null ? first.payer == null : r.payer.equals(first.payer));
			if (!same) return new Result(Kind.NEEDS_REVIEW, null, 0, "matched several formats with different results");
		}
		return first;
	}

	private Result build(FormatConfig.Format f, Matcher m) {
		Kind kind = Kind.valueOf(f.kind);
		if (kind != Kind.INCOMING_CONFIRMED) return new Result(kind, null, 0, f.id);
		String payer = group(m, "payer");
		long amount = Money.parse(group(m, "amount"));
		if (payer == null || !VALID_NAME.matcher(payer).matches() || amount <= 0) {
			return new Result(Kind.NEEDS_REVIEW, null, 0,
				"matched format '" + f.id + "' but payer or amount could not be read reliably");
		}
		return new Result(Kind.INCOMING_CONFIRMED, payer, amount, f.id);
	}

	private static String group(Matcher m, String name) {
		try {
			return m.group(name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
