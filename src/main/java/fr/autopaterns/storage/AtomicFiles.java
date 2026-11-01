package fr.autopaterns.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Écritures atomiques : le contenu est écrit dans {@code fichier.tmp}, forcé sur disque,
 * puis renommé par-dessus la cible. Un crash pendant l'écriture laisse l'ancien fichier intact.
 * Une sauvegarde de secours {@code fichier.bak} de la version précédente est conservée.
 */
public final class AtomicFiles {
	private AtomicFiles() {}

	public interface Writer {
		void write(OutputStream out) throws IOException;
	}

	public static void write(Path target, Writer writer) throws IOException {
		Path dir = target.toAbsolutePath().getParent();
		if (dir != null) Files.createDirectories(dir);
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		try (var ch = Files.newByteChannel(tmp, java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE);
			 OutputStream out = java.nio.channels.Channels.newOutputStream(ch)) {
			writer.write(out);
			out.flush();
			if (ch instanceof java.nio.channels.FileChannel fc) fc.force(true);
		} catch (IOException | RuntimeException e) {
			try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
			throw e;
		}
		if (Files.exists(target)) {
			try {
				Files.copy(target, target.resolveSibling(target.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// la copie de secours est un bonus
			}
		}
		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static void writeString(Path target, String content) throws IOException {
		byte[] data = content.getBytes(StandardCharsets.UTF_8);
		write(target, out -> out.write(data));
	}

	public static void writeLines(Path target, List<String> lines) throws IOException {
		StringBuilder sb = new StringBuilder();
		for (String l : lines) sb.append(l).append('\n');
		writeString(target, sb.toString());
	}

	/** Lit toutes les lignes UTF-8 ; si le fichier principal est illisible, tente la copie {@code .bak}. */
	public static List<String> readLinesWithBackup(Path target) throws IOException {
		try {
			return Files.readAllLines(target, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Path bak = target.resolveSibling(target.getFileName() + ".bak");
			if (Files.isRegularFile(bak)) return Files.readAllLines(bak, StandardCharsets.UTF_8);
			throw e;
		}
	}
}
