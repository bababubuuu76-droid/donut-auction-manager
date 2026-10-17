package dev.donutauction;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Auction state machine. It never sends chat, payments or items; it only records what the player
 * has legitimately seen, and it never claims a payment was refunded.
 */
public class AuctionEngine {
	private static final int MAX_REVIEWS = 200;

	private final Persistence store;
	private final Consumer<String> out;
	private Auction current;

	public AuctionEngine(Persistence store, Consumer<String> out) {
		this.store = store;
		this.out = out;
	}

	public Auction current() {
		return current;
	}

	/** Used on startup to resume an auction saved before a crash/restart. */
	public void restore(Auction saved) {
		if (saved != null && (saved.state == Auction.State.RUNNING || saved.state == Auction.State.ENDED)) {
			current = saved;
		}
	}

	public boolean isActive() {
		return current != null && (current.state == Auction.State.RUNNING || current.state == Auction.State.ENDED);
	}

	public Auction create(String host, String itemName, String itemId, int count,
						  long startBid, long minIncrement, long durationMs, String terms, long now) {
		if (isActive()) throw new IllegalStateException("An auction is already active. Close or cancel it first.");
		if (startBid < 0) throw new IllegalArgumentException("Starting bid can't be negative.");
		if (minIncrement < 1) throw new IllegalArgumentException("Minimum raise must be at least 1.");
		if (durationMs <= 0) throw new IllegalArgumentException("Duration must be positive.");
		Auction a = new Auction();
		a.id = "A" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(now));
		a.host = host;
		a.itemName = itemName;
		a.itemId = itemId;
		a.itemCount = Math.max(1, count);
		a.startBid = startBid;
		a.minIncrement = minIncrement;
		a.createdMillis = now;
		a.originalDurationMillis = durationMs;
		a.endMillis = now + durationMs;
		a.terms = terms;
		current = a;
		store.saveCurrent(a);
		return a;
	}

	// ---- timer ----

	/** Call every tick. Ends the auction when its time is up. */
	public void tick(long now) {
		Auction a = current;
		if (a == null || a.state != Auction.State.RUNNING || now < a.endMillis) return;
		finish(a);
		out.accept("Auction time is up. " + summaryLine(a));
		out.accept("Review the result, then run /aucmod winner to copy the announcement (nothing is sent automatically).");
	}

	public void endNow(long now) {
		Auction a = requireRunning();
		a.endMillis = Math.min(a.endMillis, now);
		finish(a);
	}

	public void setRemaining(long durationMs, long now) {
		Auction a = requireRunning();
		if (durationMs <= 0) throw new IllegalArgumentException("Time must be positive.");
		a.endMillis = now + durationMs;
		store.saveCurrent(a);
	}

	public void adjust(long deltaMs, long now) {
		Auction a = requireRunning();
		a.endMillis = Math.max(now, a.endMillis + deltaMs);
		store.saveCurrent(a);
	}

	public long remainingMillis(long now) {
		Auction a = current;
		return a == null || a.state != Auction.State.RUNNING ? 0 : Math.max(0, a.endMillis - now);
	}

	// ---- payments ----

	/** A payment the classifier verified as a server-confirmed incoming payment. */
	public Auction.Bid onConfirmedPayment(String payer, long amount, String raw, long when) {
		Auction a = current;
		if (a == null || !isActive()) return null;
		Auction.Bid b = evaluate(a, payer, amount, raw, when, false);
		a.bids.add(b);
		afterChange(a);
		return b;
	}

	public Auction.Review addReview(String raw, String styled, String reason, long when) {
		Auction a = current;
		if (a == null || !isActive()) return null;
		if (a.reviews.size() >= MAX_REVIEWS) return null;
		Auction.Review r = new Auction.Review();
		r.id = a.reviews.size() + 1;
		r.timeMillis = when;
		r.raw = raw;
		r.styled = styled;
		r.reason = reason;
		a.reviews.add(r);
		store.saveCurrent(a);
		return r;
	}

	/** You looked at a flagged message and confirmed payer + amount yourself. */
	public Auction.Bid acceptReview(int id, String payer, long amount) {
		Auction a = requireActive();
		Auction.Review r = findReview(a, id);
		if (r.resolved) throw new IllegalStateException("Review #" + id + " is already resolved.");
		Auction.Bid b = evaluate(a, payer, amount, r.raw, r.timeMillis, true);
		a.bids.add(b);
		r.resolved = true;
		r.resolution = "entered manually by you as " + payer + " " + Money.format(amount) + " -> " + b.status;
		afterChange(a);
		return b;
	}

	public void rejectReview(int id) {
		Auction a = requireActive();
		Auction.Review r = findReview(a, id);
		if (r.resolved) throw new IllegalStateException("Review #" + id + " is already resolved.");
		r.resolved = true;
		r.resolution = "dismissed by you";
		store.saveCurrent(a);
	}

	private Auction.Bid evaluate(Auction a, String payer, long amount, String raw, long when, boolean manual) {
		Auction.Bid b = new Auction.Bid();
		b.bidder = payer;
		b.amount = amount;
		b.timeMillis = when;
		b.rawMessage = raw;
		if (when >= a.endMillis) {
			b.status = Auction.BidStatus.LATE;
			b.note = "arrived at/after the end time; not counted";
		} else if (amount < a.requiredNext()) {
			b.status = Auction.BidStatus.REJECTED_TOO_LOW;
			b.note = "needed at least " + Money.format(a.requiredNext()) + "; not counted";
		} else {
			b.status = manual ? Auction.BidStatus.MANUAL_ACCEPTED : Auction.BidStatus.ACCEPTED;
			b.note = manual ? "confirmed manually by you" : "new highest bid";
		}
		return b;
	}

	// ---- ending ----

	private void finish(Auction a) {
		a.state = Auction.State.ENDED;
		refreshWinner(a);
		store.saveCurrent(a);
	}

	private void afterChange(Auction a) {
		if (a.state == Auction.State.ENDED) refreshWinner(a);
		store.saveCurrent(a);
	}

	private void refreshWinner(Auction a) {
		Auction.Bid h = a.highest();
		a.winner = h == null ? null : h.bidder;
		a.winningBid = h == null ? 0 : h.amount;
	}

	/** Saves the finished auction to history. Does not claim anything about refunds. */
	public Auction close() {
		Auction a = current;
		if (a == null || a.state != Auction.State.ENDED) throw new IllegalStateException("Only an ended auction can be closed.");
		a.state = Auction.State.CLOSED;
		store.appendHistory(a);
		store.clearCurrent();
		current = null;
		return a;
	}

	public Auction cancel() {
		Auction a = requireActive();
		a.state = Auction.State.CANCELLED;
		store.appendHistory(a);
		store.clearCurrent();
		current = null;
		return a;
	}

	public static String summaryLine(Auction a) {
		if (a.winner == null) return "No valid bids for " + a.itemLabel() + ".";
		return a.winner + " won " + a.itemLabel() + " with " + Money.format(a.winningBid) + ".";
	}

	private Auction requireActive() {
		if (!isActive()) throw new IllegalStateException("No active auction.");
		return current;
	}

	private Auction requireRunning() {
		if (current == null || current.state != Auction.State.RUNNING) throw new IllegalStateException("No running auction.");
		return current;
	}

	private static Auction.Review findReview(Auction a, int id) {
		for (Auction.Review r : a.reviews) if (r.id == id) return r;
		throw new IllegalArgumentException("No review item #" + id + ".");
	}
}
