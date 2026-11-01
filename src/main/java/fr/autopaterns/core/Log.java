package fr.autopaterns.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Journal centralisé du mod.
 * <ul>
 *   <li>Console du jeu : préfixe {@code [AutoPaterns] } (comme en 2.x).</li>
 *   <li>Fichier {@code autopaterns/journal/autopaterns.log} : horodaté, avec les traces d'exception,
 *       tourné à 1 Mo (l'ancien devient {@code autopaterns.1.log}).</li>
 * </ul>
 * Thread-safe : peut être appelé depuis les callbacks GPU/asynchrones.
 */
public final class Log {
	private static final String TAG = "[AutoPaterns] ";
	private static final long MAX_BYTES = 1_000_000L;
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
	private static boolean fileBroken;

	private Log() {}

	public static void info(String msg) {
		String plain = Texts.strip(msg);
		System.out.println(TAG + plain);
		toFile("INFO ", plain, null);
	}

	public static void warn(String msg) {
		String plain = Texts.strip(msg);
		System.out.println(TAG + plain);
		toFile("WARN ", plain, null);
	}

	public static void error(String msg, Throwable t) {
		String plain = Texts.strip(msg);
		System.err.println(TAG + plain);
		if (t != null) t.printStackTrace();
		toFile("ERROR", plain, t);
	}

	/** Message de débogage : uniquement dans le fichier. */
	public static void debug(String msg) {
		toFile("DEBUG", Texts.strip(msg), null);
	}

	private static synchronized void toFile(String level, String msg, Throwable t) {
		if (fileBroken) return;
		try {
			Path dir = ModPaths.logDir();
			Files.createDirectories(dir);
			Path f = dir.resolve("autopaterns.log");
			if (Files.exists(f) && Files.size(f) > MAX_BYTES) {
				Files.move(f, dir.resolve("autopaterns.1.log"), StandardCopyOption.REPLACE_EXISTING);
			}
			StringBuilder sb = new StringBuilder(128);
			sb.append(LocalDateTime.now().format(TS)).append(' ').append(level).append(' ')
				.append('[').append(Thread.currentThread().getName()).append("] ").append(msg).append('\n');
			if (t != null) {
				StringWriter sw = new StringWriter();
				t.printStackTrace(new PrintWriter(sw));
				sb.append(sw).append('\n');
			}
			Files.writeString(f, sb, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException | RuntimeException e) {
			fileBroken = true;
			System.err.println(TAG + "journal fichier désactivé : " + e);
		}
	}
}
