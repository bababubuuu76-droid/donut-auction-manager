package dev.donutauction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Local storage: current.json (crash recovery), history.json, and exports. All files stay on your machine. */
public class AuctionStore implements Persistence {
	private final Path dir;
	private final Path currentFile;
	private final Path historyFile;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	public AuctionStore(Path dir) {
		this.dir = dir;
		this.currentFile = dir.resolve("current.json");
		this.historyFile = dir.resolve("history.json");
		try {
			Files.createDirectories(dir);
		} catch (IOException ignored) {
		}
	}

	@Override
	public void saveCurrent(Auction a) {
		write(currentFile, gson.toJson(a));
	}

	@Override
	public void clearCurrent() {
		try {
			Files.deleteIfExists(currentFile);
		} catch (IOException ignored) {
		}
	}

	public Auction loadCurrent() {
		try {
			if (!Files.exists(currentFile)) return null;
			return gson.fromJson(Files.readString(currentFile), Auction.class);
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public void appendHistory(Auction a) {
		List<Auction> all = loadHistory();
		all.add(a);
		write(historyFile, gson.toJson(all));
	}

	public List<Auction> loadHistory() {
		try {
			if (!Files.exists(historyFile)) return new ArrayList<>();
			List<Auction> list = gson.fromJson(Files.readString(historyFile), new TypeToken<List<Auction>>() {}.getType());
			return list == null ? new ArrayList<>() : list;
		} catch (Exception e) {
			return new ArrayList<>();
		}
	}

	public Path exportJson() throws IOException {
		Path p = exportPath("json");
		Files.writeString(p, gson.toJson(loadHistory()));
		return p;
	}

	public Path exportCsv() throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("auction_id,state,host,item,item_count,start_bid,min_raise,winner,winning_bid,bid_time,bidder,amount,bid_status,note\n");
		DateTimeFormatter iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());
		for (Auction a : loadHistory()) {
			if (a.bids == null || a.bids.isEmpty()) {
				sb.append(row(a, "", "", "", "", ""));
				continue;
			}
			for (Auction.Bid b : a.bids) {
				sb.append(row(a, iso.format(Instant.ofEpochMilli(b.timeMillis)), b.bidder,
					Long.toString(b.amount), String.valueOf(b.status), b.note));
			}
		}
		Path p = exportPath("csv");
		Files.writeString(p, sb.toString());
		return p;
	}

	private String row(Auction a, String time, String bidder, String amount, String status, String note) {
		return String.join(",",
			csv(a.id), csv(String.valueOf(a.state)), csv(a.host), csv(a.itemName), Integer.toString(a.itemCount),
			Long.toString(a.startBid), Long.toString(a.minIncrement), csv(a.winner), Long.toString(a.winningBid),
			csv(time), csv(bidder), amount, csv(status), csv(note)) + "\n";
	}

	private static String csv(String s) {
		if (s == null) return "";
		// Neutralise spreadsheet formula injection from player-controlled names
		if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
		return "\"" + s.replace("\"", "\"\"") + "\"";
	}

	private Path exportPath(String ext) throws IOException {
		Path exports = dir.resolve("exports");
		Files.createDirectories(exports);
		String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
		return exports.resolve("auctions-" + stamp + "." + ext);
	}

	private void write(Path p, String content) {
		try {
			Files.createDirectories(dir);
			Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
			Files.writeString(tmp, content);
			Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ignored) {
		}
	}

	public Path dir() {
		return dir;
	}
}
