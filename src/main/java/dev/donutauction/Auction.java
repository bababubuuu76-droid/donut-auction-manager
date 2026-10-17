package dev.donutauction;

import java.util.ArrayList;
import java.util.List;

/** Plain data model (Gson-friendly). */
public class Auction {
	public enum State { RUNNING, ENDED, CLOSED, CANCELLED }

	public enum BidStatus {
		ACCEPTED,
		/** Accepted by you from the manual review queue. */
		MANUAL_ACCEPTED,
		/** Payment was confirmed but did not beat the current bid by the minimum raise. Not counted. */
		REJECTED_TOO_LOW,
		/** Payment arrived at or after the end time. Not counted. */
		LATE
	}

	public static class Bid {
		public String bidder;
		public long amount;
		public long timeMillis;
		public BidStatus status;
		public String note;
		public String rawMessage;
	}

	public static class Review {
		public int id;
		public long timeMillis;
		public String raw;
		public String styled;
		public String reason;
		public boolean resolved;
		public String resolution;
	}

	public String id;
	public String host;
	public String itemName;
	public String itemId;
	public int itemCount = 1;
	public long startBid;
	public long minIncrement;
	public long createdMillis;
	public long endMillis;
	public long originalDurationMillis;
	public State state = State.RUNNING;
	public String terms;
	public String winner;
	public long winningBid;
	public List<Bid> bids = new ArrayList<>();
	public List<Review> reviews = new ArrayList<>();

	public Bid highest() {
		Bid best = null;
		for (Bid b : bids) {
			boolean counts = b.status == BidStatus.ACCEPTED || b.status == BidStatus.MANUAL_ACCEPTED;
			if (counts && (best == null || b.amount > best.amount)) best = b;
		}
		return best;
	}

	/** Smallest payment that would currently become the new highest bid. */
	public long requiredNext() {
		Bid h = highest();
		return h == null ? startBid : h.amount + minIncrement;
	}

	public int pendingReviews() {
		int n = 0;
		for (Review r : reviews) if (!r.resolved) n++;
		return n;
	}

	public String itemLabel() {
		return itemCount > 1 ? itemName + " x" + itemCount : itemName;
	}
}
