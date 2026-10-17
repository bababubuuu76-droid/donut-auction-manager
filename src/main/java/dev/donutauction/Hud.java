package dev.donutauction;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * Read-only auction card in a dark and gold "high roller" style.
 * Header + timer pill, item tile with leader and minimum next bid, and a gold bid pill with a time bar.
 */
public final class Hud {
	private Hud() {}

	private static final int X = 8;
	private static final int Y = 8;
	private static final int W = 206;
	private static final int H = 70;
	private static final int PAD = 8;

	private static final int GOLD_DARK = 0xFF8A6418;
	private static final int GOLD = 0xFFD4A62A;
	private static final int GOLD_LIGHT = 0xFFFFE27A;
	private static final int GOLD_TEXT = 0xFFF2C94C;
	private static final int ON_GOLD = 0xFF2A1D00;
	private static final int CARD_TOP = 0xF0191509;
	private static final int CARD_BOTTOM = 0xF00B0A0D;
	private static final int TILE = 0xFF14110A;
	private static final int PILL = 0xFF211B0B;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int DIM = 0xFFC9B98A;
	private static final int GREEN = 0xFF4ADE80;
	private static final int RED = 0xFFFF5555;
	private static final int TRACK = 0xFF14110A;

	private static String cachedItemId = null;
	private static ItemStack cachedStack = ItemStack.EMPTY;

	public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
		Auction a = DonutAuctionMod.engine().current();
		if (a == null) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		TextRenderer tr = mc.textRenderer;
		long now = System.currentTimeMillis();
		boolean running = a.state == Auction.State.RUNNING;
		long left = running ? a.endMillis - now : 0;
		Auction.Bid h = a.highest();

		// frame: gold border, dark gradient body, bright line along the top
		roundRect(ctx, X, Y, W, H, GOLD_DARK);
		ctx.fillGradient(X + 1, Y + 1, X + W - 1, Y + H - 1, CARD_TOP, CARD_BOTTOM);
		ctx.fill(X + 6, Y + 1, X + W - 6, Y + 2, GOLD_LIGHT);

		// header + timer pill
		drawScaled(ctx, tr, Text.literal("A U C T I O N").formatted(Formatting.BOLD), X + PAD, Y + 7, 0.7f, GOLD_TEXT);
		String clock = running ? clock(left) : "ENDED";
		boolean urgent = running && left <= 10_000;
		int timerW = (int) (tr.getWidth(clock) * 0.9f) + 14;
		int timerX = X + W - PAD - timerW;
		pill(ctx, tr, clock, timerX, Y + 4, timerW, 14, 0.9f, PILL, urgent ? RED : GOLD_TEXT, true);

		// item tile with gold border
		int tile = 26;
		int tileX = X + PAD;
		int tileY = Y + 21;
		roundRect(ctx, tileX, tileY, tile, tile, GOLD_DARK);
		roundRect(ctx, tileX + 1, tileY + 1, tile - 2, tile - 2, TILE);
		ItemStack icon = iconFor(a.itemId);
		if (!icon.isEmpty()) ctx.drawItem(icon, tileX + 5, tileY + 5);

		// leader name + status line
		int textX = tileX + tile + 8;
		String title = h == null ? "No bids yet" : h.bidder;
		int titleMax = (int) ((X + W - PAD - textX) / 1.15f);
		Text titleText = Text.literal(tr.trimToWidth(title, Math.max(10, titleMax))).formatted(Formatting.BOLD);
		drawScaled(ctx, tr, titleText, textX, Y + 22, 1.15f, h == null ? DIM : WHITE);

		String status;
		int statusColor;
		if (!running) {
			status = a.winner == null ? "NO WINNER" : "WINNER";
			statusColor = a.winner == null ? DIM : GREEN;
		} else if (h == null) {
			status = "MIN " + shortMoney(a.startBid);
			statusColor = DIM;
		} else {
			status = "LEADING  -  NEXT " + shortMoney(a.requiredNext());
			statusColor = GREEN;
		}
		drawScaled(ctx, tr, Text.literal(status).formatted(Formatting.BOLD), textX, Y + 36, 0.7f, statusColor);

		// bottom row: item label (left), gold bid pill (right)
		int rowY = Y + 50;
		String bid = h == null ? "$" + shortMoney(a.startBid) : "$" + shortMoney(h.amount);
		float bidScale = 0.9f;
		int bidW = (int) (tr.getWidth(bid) * bidScale) + 16;
		int bidX = X + W - PAD - bidW;
		goldPill(ctx, tr, bid, bidX, rowY - 3, bidW, 14, bidScale);
		ctx.fill(X + PAD, rowY + 1, X + PAD + 3, rowY + 4, GOLD);
		int labelMax = (int) ((bidX - 6 - (X + PAD + 7)) / 0.8f);
		drawScaled(ctx, tr, Text.literal(tr.trimToWidth(a.itemLabel(), Math.max(10, labelMax))), X + PAD + 7, rowY, 0.8f, WHITE);

		// time bar
		int barX = X + PAD;
		int barW = W - 2 * PAD;
		int barY = Y + H - 7;
		ctx.fill(barX, barY, barX + barW, barY + 3, TRACK);
		if (running) {
			double frac = a.originalDurationMillis <= 0 ? 0 : (double) left / a.originalDurationMillis;
			frac = Math.max(0, Math.min(1, frac));
			int fill = (int) Math.round(barW * frac);
			if (fill > 0) {
				if (urgent) ctx.fill(barX, barY, barX + fill, barY + 3, RED);
				else ctx.fillGradient(barX, barY, barX + fill, barY + 3, GOLD_LIGHT, GOLD);
			}
		}

		// hints under the card
		int hintY = Y + H + 3;
		if (!running) {
			drawScaled(ctx, tr, Text.literal("/aucmod winner, then /aucmod close"), X, hintY, 0.75f, WHITE);
			hintY += 9;
		}
		int pending = a.pendingReviews();
		if (pending > 0) {
			drawScaled(ctx, tr, Text.literal(pending + " to review (/aucmod review)"), X, hintY, 0.75f, GOLD_TEXT);
		}
	}

	private static String shortMoney(long amount) {
		return Money.format(amount).replace("$", "");
	}

	/** m:ss, or h:mm:ss for longer times. */
	private static String clock(long ms) {
		long s = Math.max(0, (ms + 999) / 1000);
		long hr = s / 3600, m = (s % 3600) / 60, sec = s % 60;
		if (hr > 0) return hr + ":" + two(m) + ":" + two(sec);
		return m + ":" + two(sec);
	}

	private static String two(long v) {
		return v < 10 ? "0" + v : Long.toString(v);
	}

	/** Rectangle with the four corner pixels cut off. */
	private static void roundRect(DrawContext ctx, int x, int y, int w, int h, int color) {
		ctx.fill(x + 1, y, x + w - 1, y + h, color);
		ctx.fill(x, y + 1, x + w, y + h - 1, color);
	}

	private static void pill(DrawContext ctx, TextRenderer tr, String text, int x, int y, int w, int h,
							 float scale, int bg, int fg, boolean bold) {
		roundRect(ctx, x, y, w, h, bg);
		drawCentered(ctx, tr, text, x, y, w, h, scale, fg, bold, true);
	}

	/** Gold gradient pill with dark text, used for the highest bid. */
	private static void goldPill(DrawContext ctx, TextRenderer tr, String text, int x, int y, int w, int h, float scale) {
		roundRect(ctx, x, y, w, h, GOLD_DARK);
		ctx.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, GOLD_LIGHT, GOLD);
		drawCentered(ctx, tr, text, x, y, w, h, scale, ON_GOLD, true, false);
	}

	private static void drawCentered(DrawContext ctx, TextRenderer tr, String text, int x, int y, int w, int h,
									 float scale, int color, boolean bold, boolean shadow) {
		Text t = bold ? Text.literal(text).formatted(Formatting.BOLD) : Text.literal(text);
		float tw = tr.getWidth(t) * scale;
		float ty = y + (h - 9 * scale) / 2f + 0.5f;
		drawScaled(ctx, tr, t, x + (w - tw) / 2f, ty, scale, color, shadow);
	}

	private static void drawScaled(DrawContext ctx, TextRenderer tr, Text text, float x, float y, float scale, int color) {
		drawScaled(ctx, tr, text, x, y, scale, color, true);
	}

	private static void drawScaled(DrawContext ctx, TextRenderer tr, Text text, float x, float y, float scale, int color,
								   boolean shadow) {
		var m = ctx.getMatrices();
		m.pushMatrix();
		m.translate(x, y);
		m.scale(scale, scale);
		ctx.drawText(tr, text, 0, 0, color, shadow);
		m.popMatrix();
	}

	private static ItemStack iconFor(String itemId) {
		if (itemId == null || itemId.isEmpty()) return ItemStack.EMPTY;
		if (!itemId.equals(cachedItemId)) {
			cachedItemId = itemId;
			Identifier id = Identifier.tryParse(itemId);
			cachedStack = id == null ? ItemStack.EMPTY : new ItemStack(Registries.ITEM.get(id));
		}
		return cachedStack;
	}
}
