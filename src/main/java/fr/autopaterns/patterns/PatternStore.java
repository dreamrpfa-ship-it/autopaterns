package fr.autopaterns.patterns;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import fr.autopaterns.core.Log;
import fr.autopaterns.storage.AtomicFiles;

/**
 * Persistance des patterns, compatible avec tous les fichiers 2.x (lecture ET écriture) :
 * <pre>
 * autopaterns/dernier.apt                    dernier pattern en mémoire
 * autopaterns/pattern-yyyyMMdd-HHmmss.apt    archive de chaque fin d'enregistrement (50 plus récentes gardées)
 * autopaterns/diagnostic.txt                 journal de lecture (borné à ~512 Ko, les lignes anciennes partent)
 * autopaterns/patterns/&lt;nom&gt;.apt           patterns nommés
 * autopaterns/export/&lt;nom&gt;.apt.gz         exports (GZIP)
 * autopaterns/import/                        dépôt (*.apt, *.apt.gz) ; les fichiers importés vont dans import/deja_importes/
 * </pre>
 * En-têtes lus : {@code VOXORIA-MC 1}, {@code VOXORIA-MC 2}, {@code AUTOPATERN 3}, {@code AUTOPATERN 4} ; écrit : v4.
 * Toutes les écritures passent par {@link AtomicFiles} (fichier .tmp, renommage, copie .bak).
 */
public final class PatternStore {
	public static final String HEADER = "AUTOPATERN 4";
	public static final List<String> OLD_HEADERS = List.of("VOXORIA-MC 1", "VOXORIA-MC 2", "AUTOPATERN 3");
	public static final int MAX_TICKS = 432_000;
	public static final int MAX_FRAMES = 12_960_000;
	public static final int MAX_BREAKS = 432_000;
	public static final int MAX_OPTIONS = 64;
	/** Archives horodatées conservées (2.x : jamais purgées). */
	public static final int MAX_ARCHIVES = 50;
	private static final int DIAG_MAX_CHARS = 512 * 1024;
	private static final int DIAG_KEEP_CHARS = 384 * 1024;
	private static final int BUF = 65536;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final Pattern ARCHIVE = Pattern.compile("pattern-\\d{8}-\\d{6}(-\\d+)?\\.apt");
	private static final String IMPORTED_DIR = "deja_importes";

	/** Résumé d'un fichier (liste). {@code ticks = -1} si illisible. */
	public record Info(String name, int ticks, long modifiedMillis, long bytes) {}

	/** Contenu d'un pattern ; {@code camera} peut être null. */
	public record Loaded(List<TickFrame> frames, CameraTrack camera, List<BreakEvent> breaks, PatternOptions options) {}

	private final Path dir;

	public PatternStore(Path dir) {
		this.dir = dir;
	}

	// ------------------------------------------------------------------ chemins

	public Path dir() { return dir; }
	public Path lastFile() { return dir.resolve("dernier.apt"); }
	public Path patternsDir() { return dir.resolve("patterns"); }
	public Path exportDir() { return dir.resolve("export"); }
	public Path importDir() { return dir.resolve("import"); }
	public Path diagnosticFile() { return dir.resolve("diagnostic.txt"); }

	/**
	 * Fichier d'un pattern nommé. Le nom doit être un nom de fichier sûr ({@link #isValidName}) ; si
	 * {@code nom.apt} n'existe pas, un fichier de même nom à la casse près est accepté (corrige « X.APT » listé
	 * mais introuvable sur un système sensible à la casse).
	 */
	Path namedFile(String name) throws IOException {
		if (!isValidName(name)) throw new IOException("nom invalide");
		Path pd = patternsDir();
		Path f = pd.resolve(name + ".apt");
		if (Files.exists(f) || !Files.isDirectory(pd)) return f;
		String want = (name + ".apt").toLowerCase(Locale.ROOT);
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(pd)) {
			for (Path c : ds) {
				if (c.getFileName().toString().toLowerCase(Locale.ROOT).equals(want) && Files.isRegularFile(c)) return c;
			}
		}
		return f;
	}

	// ------------------------------------------------------------------ noms

	/** Nom assaini (2.x) : lettres, chiffres, espace, « _ », « - » ; 40 caractères ; null si vide. */
	public static String sanitize(String name) {
		if (name == null) return null;
		String s = name.trim().replaceAll("[^\\p{L}\\p{N} _-]", "_").replaceAll("\\s+", " ");
		if (s.length() > 40) s = s.substring(0, 40).trim();
		if (s.matches("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])")) s = s + "_";
		if (s.isEmpty() || s.equals(".") || s.equals("..")) return null;
		return s;
	}

	/**
	 * Nom de fichier existant acceptable tel quel (les fichiers 2.x « x (2) » ou déposés à la main restent
	 * utilisables) : pas de séparateur de chemin ni de caractère interdit, pas « . » / « .. ».
	 */
	public static boolean isValidName(String name) {
		if (name == null || name.isBlank() || name.length() > 120) return false;
		if (name.equals(".") || name.equals("..")) return false;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c < 32 || "/\\:*?\"<>|".indexOf(c) >= 0) return false;
		}
		return true;
	}

	public boolean exists(String name) {
		try {
			return Files.isRegularFile(namedFile(name));
		} catch (IOException e) {
			return false;
		}
	}

	// ------------------------------------------------------------------ opérations

	/** Fin d'enregistrement : écrit dernier.apt et une archive horodatée ; renvoie le nom de l'archive. */
	public String save(Loaded l) throws IOException {
		Path last = lastFile();
		write(last, l, false);
		String stamp = LocalDateTime.now().format(STAMP);
		String name = "pattern-" + stamp + ".apt";
		for (int n = 2; Files.exists(dir.resolve(name)) && n < 1000; n++) name = "pattern-" + stamp + "-" + n + ".apt";
		AtomicFiles.write(dir.resolve(name), out -> Files.copy(last, out));
		pruneArchives();
		return name;
	}

	public void saveLastOnly(Loaded l) throws IOException {
		write(lastFile(), l, false);
	}

	public void saveNamed(String name, Loaded l) throws IOException {
		write(namedFile(name), l, false);
	}

	public Loaded loadNamed(String name) throws IOException {
		return loadWithBackup(namedFile(name));
	}

	/** Options seules d'un pattern nommé, sans analyser ses ticks (2.x relisait tout le fichier). */
	public PatternOptions loadOptions(String name) throws IOException {
		return loadOptionsOnly(namedFile(name));
	}

	/** Supprime un pattern nommé (et sa copie .bak) ; erreur s'il n'existe pas (2.x annonçait un succès). */
	public void delete(String name) throws IOException {
		Path f = namedFile(name);
		if (!Files.deleteIfExists(f)) throw new IOException("pattern introuvable");
		try {
			Files.deleteIfExists(f.resolveSibling(f.getFileName() + ".bak"));
		} catch (IOException e) {
			Log.warn("copie de secours de « " + name + " » non supprimée : " + e);
		}
	}

	/** Renomme un pattern nommé ({@code to} doit être assaini par l'appelant). */
	public void rename(String from, String to) throws IOException {
		Path src = namedFile(from);
		if (!Files.isRegularFile(src)) throw new IOException("pattern introuvable");
		Path dst = namedFile(to);
		if (Files.exists(dst) && !Files.isSameFile(src, dst)) throw new IOException("« " + to + " » existe déjà");
		Path target = patternsDir().resolve(to + ".apt");
		Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
		Path bak = src.resolveSibling(src.getFileName() + ".bak");
		if (Files.exists(bak)) {
			try {
				Files.move(bak, target.resolveSibling(target.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				Log.warn("copie de secours de « " + from + " » non renommée : " + e);
			}
		}
	}

	public List<Info> list() {
		return listDir(patternsDir(), false);
	}

	/** Exporte le fichier nommé (relu sur disque) en {@code export/<nom>.apt.gz}. */
	public Path export(String name) throws IOException {
		Path target = exportDir().resolve(name + ".apt.gz");
		write(target, loadNamed(name), true);
		return target;
	}

	public List<Info> importable() {
		return listDir(importDir(), true);
	}

	/**
	 * Importe {@code import/<fileName>} sous un nom assaini et unique (« nom-2 », « nom-3 »… : stable au
	 * redémarrage, contrairement aux « nom (2) » de la 2.x) puis range le fichier source dans
	 * {@code import/deja_importes/} (évite les doublons à chaque clic). Renvoie le nom du pattern créé.
	 */
	public String importFile(String fileName) throws IOException {
		Path base = importDir().toAbsolutePath().normalize();
		Path p = base.resolve(fileName).normalize();
		if (!p.startsWith(base) || !p.getParent().equals(base) || !Files.isRegularFile(p)) {
			throw new IOException("fichier introuvable");
		}
		Loaded l = load(p);
		if (l.frames().isEmpty()) throw new IOException("pattern vide");
		String s = sanitize(fileName.replaceFirst("(?i)\\.apt(\\.gz)?$", ""));
		if (s == null) s = "importé";
		String cand = uniqueName(s);
		saveNamed(cand, l);
		try {
			Path done = base.resolve(IMPORTED_DIR);
			Files.createDirectories(done);
			Path t = done.resolve(p.getFileName());
			if (Files.exists(t)) t = done.resolve(LocalDateTime.now().format(STAMP) + "-" + p.getFileName());
			Files.move(p, t, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			Log.warn("fichier importé laissé dans le dossier d'import : " + e);
		}
		return cand;
	}

	/** Nom libre dérivé de {@code s} (assaini) : {@code s}, {@code s-2}, {@code s-3}… (40 caractères max). */
	public String uniqueName(String s) {
		if (!exists(s)) return s;
		for (int n = 2; n < 10_000; n++) {
			String suffix = "-" + n;
			String b = s.length() + suffix.length() > 40 ? s.substring(0, 40 - suffix.length()).trim() : s;
			String cand = b + suffix;
			if (!exists(cand)) return cand;
		}
		return s + "-" + System.currentTimeMillis();
	}

	/** Ajoute une ligne horodatée à diagnostic.txt (fichier borné : les plus anciennes lignes sont retirées). */
	public synchronized void appendDiagnostic(String text) throws IOException {
		Files.createDirectories(dir);
		Path f = diagnosticFile();
		String line = LocalDateTime.now().format(STAMP) + " · " + text + "\n";
		String old = Files.isRegularFile(f) ? new String(Files.readAllBytes(f), StandardCharsets.UTF_8) : "";
		String all = old + line;
		if (all.length() > DIAG_MAX_CHARS) {
			int nl = all.indexOf('\n', all.length() - DIAG_KEEP_CHARS);
			all = nl >= 0 && nl + 1 < all.length() ? all.substring(nl + 1) : line;
		}
		AtomicFiles.writeString(f, all);
	}

	/** dernier.apt, ou null s'il n'existe pas. */
	public Loaded loadLast() throws IOException {
		Path f = lastFile();
		if (!Files.exists(f)) return null;
		return loadWithBackup(f);
	}

	// ------------------------------------------------------------------ archives

	private void pruneArchives() {
		List<Path> archives = new ArrayList<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
			for (Path p : ds) {
				if (ARCHIVE.matcher(p.getFileName().toString()).matches() && Files.isRegularFile(p)) archives.add(p);
			}
		} catch (IOException | RuntimeException e) {
			Log.warn("archives non triées : " + e);
			return;
		}
		if (archives.size() <= MAX_ARCHIVES) return;
		archives.sort(Comparator.comparingLong(PatternStore::mtime).reversed()
			.thenComparing(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()));
		for (int i = MAX_ARCHIVES; i < archives.size(); i++) {
			try {
				Files.deleteIfExists(archives.get(i));
			} catch (IOException e) {
				Log.warn("archive non supprimée : " + archives.get(i).getFileName() + " : " + e);
			}
		}
	}

	private static long mtime(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0L;
		}
	}

	// ------------------------------------------------------------------ liste

	/**
	 * Liste un dossier (patterns : nom sans extension ; import : nom de fichier complet, .apt.gz accepté).
	 * Une entrée illisible est ignorée sans interrompre la liste, toujours triée du plus récent au plus ancien.
	 */
	private static List<Info> listDir(Path d, boolean allowGz) {
		List<Info> result = new ArrayList<>();
		if (!Files.isDirectory(d)) return result;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
			for (Path p : ds) {
				try {
					String fn = p.getFileName().toString();
					String lower = fn.toLowerCase(Locale.ROOT);
					boolean isGz = lower.endsWith(".apt.gz");
					if (!Files.isRegularFile(p)) continue;
					if (!lower.endsWith(".apt") && !(allowGz && isGz)) continue;
					String name = allowGz ? fn : fn.substring(0, fn.length() - 4);
					if (!allowGz && !isValidName(name)) continue;
					int ticks = -1;
					try (BufferedReader r = reader(p)) {
						r.readLine();
						String l2 = r.readLine();
						if (l2 != null && l2.startsWith("ticks")) ticks = Integer.parseInt(l2.substring(5).trim());
					} catch (IOException | RuntimeException e) {
						ticks = -1;
					}
					result.add(new Info(name, ticks, Files.getLastModifiedTime(p).toMillis(), Files.size(p)));
				} catch (IOException | RuntimeException e) {
					Log.warn("fichier ignoré dans la liste : " + p.getFileName() + " : " + e);
				}
			}
		} catch (IOException | RuntimeException e) {
			Log.warn("dossier illisible : " + d + " : " + e);
		}
		result.sort(Comparator.comparingLong(Info::modifiedMillis).reversed());
		return result;
	}

	// ------------------------------------------------------------------ format

	private static BufferedReader reader(Path p) throws IOException {
		InputStream in = Files.newInputStream(p);
		try {
			if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")) in = new GZIPInputStream(in, BUF);
		} catch (IOException e) {
			in.close();
			throw e;
		}
		return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), BUF);
	}

	/** Lit {@code f} ; s'il est illisible et qu'une copie .bak existe, la relit (avec un avertissement). */
	private static Loaded loadWithBackup(Path f) throws IOException {
		try {
			return load(f);
		} catch (IOException e) {
			Path bak = f.resolveSibling(f.getFileName() + ".bak");
			if (!Files.isRegularFile(bak)) throw e;
			try {
				Loaded l = load(bak);
				Log.warn(f.getFileName() + " illisible (" + e.getMessage() + "), copie de secours .bak utilisée");
				return l;
			} catch (IOException e2) {
				throw e;
			}
		}
	}

	/** Lecteur de lignes avec numéro de ligne exact (2.x oubliait de compter les lignes d'en-tête de section). */
	private static final class Lines implements Closeable {
		private final BufferedReader r;
		private int line;

		Lines(BufferedReader r) {
			this.r = r;
		}

		String read() throws IOException {
			String s = r.readLine();
			if (s != null) line++;
			return s;
		}

		String next(String label, int i, int total) throws IOException {
			String s = read();
			if (s == null) throw new IOException(label + " tronqué(e) (" + i + "/" + total + ")");
			return s;
		}

		<T> T parse(Function<String, T> fn, String label, int i, int total) throws IOException {
			String s = next(label, i, total);
			try {
				return fn.apply(s);
			} catch (RuntimeException e) {
				throw new IOException("ligne " + line + " : " + e.getMessage());
			}
		}

		int count(String key, int max) throws IOException {
			String s = read();
			if (s == null || !s.startsWith(key)) throw new IOException("en-tête « " + key.trim() + " » manquant (ligne " + (line + 1) + ")");
			int v = Integer.parseInt(s.substring(key.length()).trim());
			if (v < 0 || v > max) throw new IOException("nombre invalide : " + v);
			return v;
		}

		@Override
		public void close() throws IOException {
			r.close();
		}
	}

	/** Version du fichier d'après son en-tête : 1..4, ou exception. */
	private static int version(String header) throws IOException {
		if (header == null) throw new IOException("fichier vide");
		String h = header.startsWith("﻿") ? header.substring(1) : header;
		h = h.trim();
		return switch (h) {
			case HEADER -> 4;
			case "AUTOPATERN 3" -> 3;
			case "VOXORIA-MC 2" -> 2;
			case "VOXORIA-MC 1" -> 1;
			default -> throw new IOException("ce n'est pas un pattern AutoPaterns");
		};
	}

	/** Lecture complète d'un fichier .apt ou .apt.gz (toutes versions). */
	static Loaded load(Path path) throws IOException {
		try (Lines in = new Lines(reader(path))) {
			int v = version(in.read());
			boolean hasBreaks = v >= 3;
			boolean hasCamera = v >= 2;
			int n = in.count("ticks", MAX_TICKS);
			List<TickFrame> frames = new ArrayList<>(n);
			for (int i = 0; i < n; i++) frames.add(in.parse(TickFrame::parse, "ticks", i, n));
			CameraTrack camera = null;
			if (hasCamera) {
				int c = in.count("frames", MAX_FRAMES);
				camera = new CameraTrack();
				for (int i = 0; i < c; i++) {
					String s = in.next("piste caméra", i, c);
					String[] p = s.trim().split("\\s+");
					if (p.length != 3) throw new IOException("ligne " + in.line + " : 3 valeurs attendues");
					double t;
					float yaw, pitch;
					try {
						t = Double.parseDouble(p[0]);
						yaw = Float.parseFloat(p[1]);
						pitch = Float.parseFloat(p[2]);
					} catch (NumberFormatException e) {
						throw new IOException("ligne " + in.line + " : " + e.getMessage());
					}
					if (!Double.isFinite(t) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
						throw new IOException("ligne " + in.line + " : valeur invalide");
					}
					camera.add(t, yaw, pitch);
				}
				if (camera.size() < 2) camera = null;
			}
			List<BreakEvent> breaks = new ArrayList<>();
			if (hasBreaks) {
				int b = in.count("breaks", MAX_BREAKS);
				for (int i = 0; i < b; i++) breaks.add(in.parse(BreakEvent::parse, "blocs cassés", i, b));
			}
			PatternOptions options = new PatternOptions();
			if (v >= 4) {
				int k = in.count("options", MAX_OPTIONS);
				List<String> lines = new ArrayList<>(k);
				for (int i = 0; i < k; i++) lines.add(in.next("options", i, k));
				options = PatternOptions.parse(lines);
			}
			return new Loaded(frames, camera, breaks, options);
		} catch (NumberFormatException e) {
			throw new IOException("nombre illisible : " + e.getMessage());
		}
	}

	/** Lecture des seules options (les autres sections sont sautées sans analyse). */
	static PatternOptions loadOptionsOnly(Path path) throws IOException {
		try (Lines in = new Lines(reader(path))) {
			int v = version(in.read());
			int n = in.count("ticks", MAX_TICKS);
			for (int i = 0; i < n; i++) in.next("ticks", i, n);
			if (v >= 2) {
				int c = in.count("frames", MAX_FRAMES);
				for (int i = 0; i < c; i++) in.next("piste caméra", i, c);
			}
			if (v >= 3) {
				int b = in.count("breaks", MAX_BREAKS);
				for (int i = 0; i < b; i++) in.next("blocs cassés", i, b);
			}
			if (v < 4) return new PatternOptions();
			int k = in.count("options", MAX_OPTIONS);
			List<String> lines = new ArrayList<>(k);
			for (int i = 0; i < k; i++) lines.add(in.next("options", i, k));
			return PatternOptions.parse(lines);
		} catch (NumberFormatException e) {
			throw new IOException("nombre illisible : " + e.getMessage());
		}
	}

	/** Écriture v4 atomique (GZIP si demandé). Fins de ligne « \n » quel que soit le système. */
	private static void write(Path path, Loaded l, boolean gzip) throws IOException {
		AtomicFiles.write(path, out -> {
			OutputStream base = new KeepOpen(out);
			OutputStream o = gzip ? new GZIPOutputStream(base, BUF) : base;
			try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(o, StandardCharsets.UTF_8), BUF)) {
				writeBody(w, l);
			}
		});
	}

	private static void writeBody(BufferedWriter w, Loaded l) throws IOException {
		List<TickFrame> frames = l.frames() == null ? List.of() : l.frames();
		w.write(HEADER);
		w.write('\n');
		w.write("ticks " + frames.size());
		w.write('\n');
		for (TickFrame f : frames) {
			w.write(f.serialize());
			w.write('\n');
		}
		CameraTrack cam = l.camera();
		int c = cam == null ? 0 : cam.size();
		w.write("frames " + c);
		w.write('\n');
		for (int i = 0; i < c; i++) {
			w.write(cam.timeAt(i) + " " + cam.yawAt(i) + " " + cam.pitchAt(i));
			w.write('\n');
		}
		List<BreakEvent> breaks = l.breaks() == null ? List.of() : l.breaks();
		w.write("breaks " + breaks.size());
		w.write('\n');
		for (BreakEvent b : breaks) {
			w.write(b.serialize());
			w.write('\n');
		}
		List<String> opts = (l.options() == null ? new PatternOptions() : l.options()).serialize();
		w.write("options " + opts.size());
		w.write('\n');
		for (String s : opts) {
			w.write(s);
			w.write('\n');
		}
	}

	/** Flux qui ne ferme pas le flux d'AtomicFiles (celui-ci le force sur disque puis le ferme lui-même). */
	private static final class KeepOpen extends FilterOutputStream {
		KeepOpen(OutputStream out) {
			super(out);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			out.write(b, off, len);
		}

		@Override
		public void close() throws IOException {
			out.flush();
		}
	}
}
