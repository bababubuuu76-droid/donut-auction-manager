package dev.donutauction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Optional recorder of messages you receive, so you can copy REAL formats into config.json.
 * Off by default. Writes only to capture.log on your own machine.
 */
public class CaptureLog {
	private final Path file;
	private volatile boolean enabled;

	public CaptureLog(Path dir) {
		this.file = dir.resolve("capture.log");
	}

	public boolean enabled() {
		return enabled;
	}

	public void setEnabled(boolean on) {
		enabled = on;
	}

	public Path file() {
		return file;
	}

	public void log(String channel, String plain, String styled) {
		if (!enabled) return;
		String line = Instant.now() + "\t" + channel + "\t" + clean(plain) + "\t" + clean(styled) + "\n";
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ignored) {
		}
	}

	private static String clean(String s) {
		return s.replace("\t", "    ").replace("\n", "\\n").replace("\r", "");
	}
}
