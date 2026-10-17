package dev.donutauction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ConfigLoader {
	private ConfigLoader() {}

	public static final class Loaded {
		public final FormatConfig config;
		public final List<String> problems;

		Loaded(FormatConfig config, List<String> problems) {
			this.config = config;
			this.problems = problems;
		}
	}

	/** Loads config.json, creating it (with an EMPTY formats list) if it doesn't exist. */
	public static Loaded load(Path dir) {
		Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
		Path file = dir.resolve("config.json");
		List<String> problems = new ArrayList<>();
		FormatConfig cfg = null;
		try {
			Files.createDirectories(dir);
			if (Files.exists(file)) {
				cfg = gson.fromJson(Files.readString(file), FormatConfig.class);
			} else {
				cfg = new FormatConfig();
				Files.writeString(file, gson.toJson(cfg));
			}
		} catch (Exception e) {
			problems.add("Could not read config.json: " + e.getMessage() + " (using defaults)");
		}
		if (cfg == null) cfg = new FormatConfig();
		problems.addAll(cfg.compile());
		return new Loaded(cfg, problems);
	}
}
