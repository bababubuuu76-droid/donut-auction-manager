package dev.donutauction;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Client-side auction manager. Read-only with respect to the server: it only reads messages you
 * receive and shows information. It never sends chat, payments or items on its own.
 */
public class DonutAuctionMod implements ClientModInitializer {
	public static final String ID = "donut-auction-manager";
	private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.]{1,17}$");

	private static AuctionEngine engine;
	private static AuctionStore store;
	private static FormatConfig config;
	private static MessageClassifier classifier;
	private static CaptureLog capture;
	private static final Map<String, Long> recent = new HashMap<>();
	private static final List<String> pendingNotices = new ArrayList<>();

	public static AuctionEngine engine() {
		return engine;
	}

	@Override
	public void onInitializeClient() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve(ID);
		store = new AuctionStore(dir);
		capture = new CaptureLog(dir);
		ConfigLoader.Loaded loaded = ConfigLoader.load(dir);
		config = loaded.config;
		classifier = new MessageClassifier(config);
		engine = new AuctionEngine(store, DonutAuctionMod::info);
		engine.restore(store.loadCurrent());

		for (String p : loaded.problems) info("Config problem: " + p);
		if (config.formats.isEmpty()) {
			info("No payment formats configured yet, so payments can't be auto-detected. Run /aucmod capture on and see the README.");
		}
		if (engine.current() != null) info("Restored unfinished auction: " + engine.current().itemLabel());

		// Server system messages and action bar text. Player chat is a separate event and is never parsed as a payment.
		ClientReceiveMessageEvents.GAME.register((msg, overlay) ->
			onMessage(overlay ? MessageClassifier.Channel.ACTIONBAR : MessageClassifier.Channel.SYSTEM, msg));
		ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) ->
			onMessage(MessageClassifier.Channel.CHAT, msg));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player != null && !pendingNotices.isEmpty()) {
				for (String s : pendingNotices) info(s);
				pendingNotices.clear();
			}
			engine.tick(System.currentTimeMillis());
		});

		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.of(ID, "hud"), Hud::render);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(buildCommands()));
	}

	// ------------------------------------------------------------------ message intake

	private static void onMessage(MessageClassifier.Channel ch, Text text) {
		String plain = text.getString();
		String styled = styled(text);
		capture.log(ch.name(), plain, styled);
		if (ch == MessageClassifier.Channel.CHAT) return; // player chat is never a payment confirmation

		long now = System.currentTimeMillis();
		String key = ch + "|" + plain;
		Long prev = recent.get(key);
		if (prev != null && now - prev < config.dedupeWindowMs) return; // duplicate event
		recent.put(key, now);
		if (recent.size() > 500) recent.values().removeIf(t -> now - t > 60_000);

		if (!engine.isActive()) return;
		MessageClassifier.Result r = classifier.classify(ch, plain, styled);
		switch (r.kind) {
			case INCOMING_CONFIRMED -> {
				if (r.payer.equalsIgnoreCase(selfName())) {
					info("Ignored a confirmation that names you as the payer.");
					return;
				}
				Auction.Bid b = engine.onConfirmedPayment(r.payer, r.amount, plain, now);
				if (b == null) return;
				switch (b.status) {
					case ACCEPTED -> info("NEW HIGH BID: " + b.bidder + " " + Money.format(b.amount));
					default -> info(b.bidder + " paid " + Money.format(b.amount) + " - not counted as a bid: " + b.note);
				}
			}
			case FAILED -> info("Failed-payment message seen (not counted): " + plain);
			case NEEDS_REVIEW -> {
				Auction.Review rv = engine.addReview(plain, styled, r.reason, now);
				if (rv != null) {
					info("Needs your review #" + rv.id + ": \"" + plain + "\" (" + r.reason + "). "
						+ "/aucmod accept " + rv.id + " <player> <amount>  or  /aucmod reject " + rv.id);
				}
			}
			default -> { /* outgoing confirmations and unrelated messages are ignored */ }
		}
	}

	/** Plain text with colour info: coloured segments become "<color>text</>". For use in config.json formats. */
	private static String styled(Text text) {
		StringBuilder sb = new StringBuilder();
		text.<Void>visit((style, s) -> {
			TextColor c = style.getColor();
			if (c != null && !s.isEmpty()) sb.append('<').append(c.getName()).append('>').append(s).append("</>");
			else sb.append(s);
			return Optional.empty();
		}, Style.EMPTY);
		return sb.toString();
	}

	// ------------------------------------------------------------------ commands

	private interface Cmd {
		void run(CommandContext<FabricClientCommandSource> ctx) throws Exception;
	}

	private static Command<FabricClientCommandSource> exec(Cmd c) {
		return ctx -> {
			try {
				c.run(ctx);
				return 1;
			} catch (IllegalArgumentException | IllegalStateException e) {
				err(e.getMessage());
				return 0;
			} catch (Exception e) {
				err("Error: " + e);
				return 0;
			}
		};
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildCommands() {
		return literal("aucmod")
			.executes(exec(ctx -> info("Commands: create, status, rules, time, end, winner, close, cancel, review, accept, reject, history, export, capture, reload")))
			.then(literal("create")
				.then(argument("duration", StringArgumentType.word())
					.then(argument("start", StringArgumentType.word())
						.then(argument("raise", StringArgumentType.word())
							.executes(exec(ctx -> create(ctx, null)))
							.then(argument("item", StringArgumentType.greedyString())
								.executes(exec(ctx -> create(ctx, StringArgumentType.getString(ctx, "item")))))))))
			.then(literal("status").executes(exec(ctx -> status())))
			.then(literal("rules")
				.executes(exec(ctx -> rules(1)))
				.then(argument("line", IntegerArgumentType.integer(1, 5))
					.executes(exec(ctx -> rules(IntegerArgumentType.getInteger(ctx, "line"))))))
			.then(literal("time")
				.then(literal("set").then(argument("duration", StringArgumentType.word())
					.executes(exec(ctx -> {
						engine.setRemaining(duration(ctx), System.currentTimeMillis());
						info("Time left set to " + Fmt.duration(engine.remainingMillis(System.currentTimeMillis())) + ".");
					}))))
				.then(literal("add").then(argument("duration", StringArgumentType.word())
					.executes(exec(ctx -> {
						engine.adjust(duration(ctx), System.currentTimeMillis());
						info("Time left is now " + Fmt.duration(engine.remainingMillis(System.currentTimeMillis())) + ".");
					}))))
				.then(literal("sub").then(argument("duration", StringArgumentType.word())
					.executes(exec(ctx -> {
						engine.adjust(-duration(ctx), System.currentTimeMillis());
						info("Time left is now " + Fmt.duration(engine.remainingMillis(System.currentTimeMillis())) + ".");
					})))))
			.then(literal("end").executes(exec(ctx -> {
				engine.endNow(System.currentTimeMillis());
				Auction a = engine.current();
				info("Auction ended early. " + AuctionEngine.summaryLine(a));
				info("Run /aucmod winner to copy the announcement (nothing is sent automatically).");
			})))
			.then(literal("winner").executes(exec(ctx -> winner())))
			.then(literal("close").executes(exec(ctx -> {
				Auction a = engine.close();
				info("Saved to history: " + AuctionEngine.summaryLine(a));
				if (a.winner != null) {
					info("Reminder: deliver " + a.itemLabel() + " to " + a.winner + " yourself. This mod can't and won't transfer items.");
				}
			})))
			.then(literal("cancel").executes(exec(ctx -> {
				engine.cancel();
				info("Auction cancelled and saved to history. Payments already received are unaffected by this mod.");
			})))
			.then(literal("review").executes(exec(ctx -> review())))
			.then(literal("accept")
				.then(argument("id", IntegerArgumentType.integer(1))
					.then(argument("player", StringArgumentType.word())
						.then(argument("amount", StringArgumentType.word())
							.executes(exec(ctx -> {
								String player = StringArgumentType.getString(ctx, "player");
								long amount = Money.parse(StringArgumentType.getString(ctx, "amount"));
								if (!NAME.matcher(player).matches()) throw new IllegalArgumentException("That isn't a valid Minecraft username.");
								if (amount <= 0) throw new IllegalArgumentException("Bad amount. Use e.g. 1500, 2.5k, 1m.");
								Auction.Bid b = engine.acceptReview(IntegerArgumentType.getInteger(ctx, "id"), player, amount);
								info("Recorded " + b.bidder + " " + Money.format(b.amount) + " -> " + b.status + (b.note == null ? "" : " (" + b.note + ")"));
							}))))))
			.then(literal("reject")
				.then(argument("id", IntegerArgumentType.integer(1))
					.executes(exec(ctx -> {
						engine.rejectReview(IntegerArgumentType.getInteger(ctx, "id"));
						info("Review item dismissed.");
					}))))
			.then(literal("history").executes(exec(ctx -> history())))
			.then(literal("export")
				.then(literal("json").executes(exec(ctx -> info("Exported to " + store.exportJson()))))
				.then(literal("csv").executes(exec(ctx -> info("Exported to " + store.exportCsv())))))
			.then(literal("capture")
				.then(literal("on").executes(exec(ctx -> {
					capture.setEnabled(true);
					info("Capture ON. Messages you receive are being written to " + capture.file()
						+ " on your computer. Turn it off with /aucmod capture off when you're done.");
				})))
				.then(literal("off").executes(exec(ctx -> {
					capture.setEnabled(false);
					info("Capture OFF.");
				}))))
			.then(literal("reload").executes(exec(ctx -> {
				ConfigLoader.Loaded l = ConfigLoader.load(store.dir());
				config = l.config;
				classifier = new MessageClassifier(config);
				info("Config reloaded: " + config.formats.size() + " format(s), " + l.problems.size() + " problem(s).");
				for (String p : l.problems) info("Config problem: " + p);
			})));
	}

	private static long duration(CommandContext<FabricClientCommandSource> ctx) {
		long ms = Fmt.parseDuration(StringArgumentType.getString(ctx, "duration"));
		if (ms < 0) throw new IllegalArgumentException("Bad duration. Examples: 90, 10m, 1h30m (max 7d).");
		return ms;
	}

	private static void create(CommandContext<FabricClientCommandSource> ctx, String itemOverride) {
		long dur = duration(ctx);
		long start = Money.parse(StringArgumentType.getString(ctx, "start"));
		long raise = Money.parse(StringArgumentType.getString(ctx, "raise"));
		if (start < 0) throw new IllegalArgumentException("Bad starting bid. Use e.g. 1000, 50k, 1.5m (or 0).");
		if (raise < 1) throw new IllegalArgumentException("Bad minimum raise. Use e.g. 100, 5k (at least 1).");

		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) throw new IllegalStateException("Join a world first.");
		ItemStack st = mc.player.getMainHandStack();
		String name;
		String id = "";
		int count = 1;
		if (!st.isEmpty()) {
			id = Registries.ITEM.getId(st.getItem()).toString();
			count = st.getCount();
		}
		if (itemOverride != null && !itemOverride.isBlank()) {
			name = itemOverride.trim();
		} else if (!st.isEmpty()) {
			name = st.getName().getString();
		} else {
			throw new IllegalArgumentException("Hold the item you're auctioning, or add a name: /aucmod create 10m 1k 100 Diamond Sword");
		}
		long now = System.currentTimeMillis();
		Auction a = engine.create(selfName(), name, id, count, start, raise, dur, config.terms, now);
		info("Auction created: " + a.itemLabel() + ", start " + Money.format(start) + ", min raise " + Money.format(raise)
			+ ", " + Fmt.duration(dur) + ".");
		info("Run /aucmod rules to copy the announcement text. You send it yourself.");
		copyAndShow(Announcements.rules(a, now), 1);
	}

	private static void rules(int line) {
		Auction a = requireAuction();
		copyAndShow(Announcements.rules(a, System.currentTimeMillis()), line);
	}

	private static void copyAndShow(List<String> lines, int line) {
		int idx = Math.min(Math.max(line, 1), lines.size());
		for (int i = 0; i < lines.size(); i++) info("Line " + (i + 1) + ": " + lines.get(i));
		MinecraftClient.getInstance().keyboard.setClipboard(lines.get(idx - 1));
		info("Line " + idx + " copied to your clipboard. Paste it into chat (Ctrl+V) and press Enter yourself."
			+ (lines.size() > 1 ? " /aucmod rules <n> copies another line." : ""));
	}

	private static void status() {
		Auction a = requireAuction();
		long now = System.currentTimeMillis();
		info("Item: " + a.itemLabel() + " | state " + a.state
			+ (a.state == Auction.State.RUNNING ? " | " + Fmt.duration(a.endMillis - now) + " left" : ""));
		Auction.Bid h = a.highest();
		info(h == null ? "No valid bids yet (start " + Money.format(a.startBid) + ")"
			: "Highest: " + h.bidder + " " + Money.format(h.amount));
		if (a.state == Auction.State.RUNNING) info("Next bid must be at least " + Money.format(a.requiredNext()));
		listBids(a);
		if (a.pendingReviews() > 0) info("Messages waiting for your review: " + a.pendingReviews());
	}

	private static void listBids(Auction a) {
		for (Auction.Bid b : a.bids) {
			info("  " + b.bidder + " " + Money.format(b.amount) + " [" + b.status + "]" + (b.note == null ? "" : " " + b.note));
		}
	}

	private static void winner() {
		Auction a = requireAuction();
		if (a.state != Auction.State.ENDED) throw new IllegalStateException("The auction hasn't ended yet.");
		info("=== WINNER SUMMARY ===");
		info(AuctionEngine.summaryLine(a));
		listBids(a);
		if (a.pendingReviews() > 0) info("Warning: " + a.pendingReviews() + " flagged message(s) are still unreviewed and could change the result.");
		String line = Announcements.winner(a);
		MinecraftClient.getInstance().keyboard.setClipboard(line);
		info("Announcement copied to your clipboard: \"" + line + "\"");
		info("Nothing was sent. Paste it into chat and press Enter if you're happy with it. Then /aucmod close to save.");
		if (a.winner != null) info("Reminder: deliver the item to " + a.winner + " yourself.");
	}

	private static void review() {
		Auction a = requireAuction();
		int n = 0;
		for (Auction.Review r : a.reviews) {
			if (r.resolved) continue;
			info("#" + r.id + ": \"" + r.raw + "\" (" + r.reason + ")");
			n++;
		}
		if (n == 0) info("Nothing to review.");
		else info("Use /aucmod accept <id> <player> <amount> only if you've verified the payment yourself, or /aucmod reject <id>.");
	}

	private static void history() {
		List<Auction> all = store.loadHistory();
		if (all.isEmpty()) {
			info("No saved auctions yet.");
			return;
		}
		int from = Math.max(0, all.size() - 5);
		for (int i = from; i < all.size(); i++) {
			Auction a = all.get(i);
			info(a.id + " [" + a.state + "] " + AuctionEngine.summaryLine(a));
		}
		info("/aucmod export json|csv writes the full history to " + store.dir().resolve("exports"));
	}

	private static Auction requireAuction() {
		Auction a = engine.current();
		if (a == null) throw new IllegalStateException("No active auction.");
		return a;
	}

	// ------------------------------------------------------------------ helpers

	private static String selfName() {
		MinecraftClient mc = MinecraftClient.getInstance();
		return mc.getSession().getUsername();
	}

	/** Shows a message only to you (local chat). Queued if no world is loaded yet. */
	static void info(String msg) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) {
			pendingNotices.add(msg);
			return;
		}
		mc.player.sendMessage(Text.literal("[Auction] " + msg).formatted(Formatting.GOLD), false);
	}

	private static void err(String msg) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) mc.player.sendMessage(Text.literal("[Auction] " + msg).formatted(Formatting.RED), false);
	}
}
