package com.dealanalyzer.document;

import com.dealanalyzer.document.DocumentFileInspector.InspectedDocument;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** All untrusted document parsing crosses this process seam; no case credentials cross it. */
@Component
class DocumentParserProcess {
  static final int MAX_INPUT_BYTES = 20 * 1024 * 1024;
  static final int MAX_PAGE_BYTES = 64 * 1024 * 1024;
  static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of("application/pdf", "image/png", "image/jpeg", "image/heic");
  private final Settings settings;
  private final Duration timeout;
  private final int heapMb;
  private final Semaphore slots;
  private final ExecutorService io;
  private final Set<Process> active = ConcurrentHashMap.newKeySet();
  private ContainerParserRuntime container;

  @org.springframework.beans.factory.annotation.Autowired
  DocumentParserProcess(Environment environment) {
    this(Settings.from(environment), environment.getProperty("document.parser.timeout", Duration.class, Duration.ofSeconds(20)),
        environment.getProperty("document.parser.heap-mb", Integer.class, 512),
        environment.getProperty("document.parser.max-concurrent", Integer.class, 2));
    String mode = environment.getProperty("document.parser.mode", "process");
    if (mode.equals("container")) container = new ContainerParserRuntime(environment);
    else if (!mode.equals("process")) throw new IllegalArgumentException("Unknown document parser mode");
    if (environment.matchesProfiles("pilot") && container == null) {
      throw new IllegalArgumentException("Pilot requires confined document parsing");
    }
  }

  DocumentParserProcess(Settings settings, Duration timeout, int heapMb, int concurrency) {
    if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(1)) > 0
        || heapMb < 64 || heapMb > 1024 || concurrency < 1 || concurrency > 8) {
      throw new IllegalArgumentException("Invalid document parser resource limits");
    }
    this.settings = settings; this.timeout = timeout; this.heapMb = heapMb;
    slots = new Semaphore(concurrency);
    io = Executors.newFixedThreadPool(concurrency, runnable -> {
      var thread = new Thread(runnable, "document-parser-pipe"); thread.setDaemon(true); return thread;
    });
  }

  InspectedDocument inspect(byte[] bytes) {
    return call("inspect", bytes, 0, input -> new InspectedDocument(
        mediaType(input.readUTF()), bounded(input.readInt(), 1, settings.maxPages()),
        nullablePositive(input.readInt()), nullablePositive(input.readInt()), null,
        false, input.readBoolean(), input.readBoolean(), input.readBoolean()));
  }

  Page render(byte[] bytes, int pageNumber) {
    return call("render", bytes, pageNumber, input -> {
      int width = bounded(input.readInt(), 1, 40_000_000);
      int height = bounded(input.readInt(), 1, 40_000_000);
      if ((long) width * height > settings.maxPagePixels()) throw new IOException("Invalid page dimensions");
      byte[] png = readBytes(input, MAX_PAGE_BYTES);
      if (png.length < 33 || !Arrays.equals(Arrays.copyOf(png, 8),
          new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})
          || java.nio.ByteBuffer.wrap(png, 8, 4).getInt() != 13
          || java.nio.ByteBuffer.wrap(png, 12, 4).getInt() != 0x49484452
          || java.nio.ByteBuffer.wrap(png, 16, 4).getInt() != width
          || java.nio.ByteBuffer.wrap(png, 20, 4).getInt() != height) throw new IOException("Invalid normalized page");
      return new Page(png, width, height, settings.dpi());
    });
  }

  List<String> text(byte[] bytes, int expectedPages) {
    return call("text", bytes, expectedPages, input -> {
      int count = bounded(input.readInt(), 1, settings.maxPages());
      if (count != expectedPages) throw new IOException("Page count changed");
      List<String> pages = new ArrayList<>();
      for (int i = 0; i < count; i++) pages.add(new String(readBytes(input, 400_000), java.nio.charset.StandardCharsets.UTF_8));
      return List.copyOf(pages);
    });
  }

  private <T> T call(String operation, byte[] bytes, int page, Response<T> response) {
    if (bytes.length == 0) throw rejected("EMPTY_FILE");
    if (bytes.length > settings.maxFileBytes()) throw rejected("FILE_TOO_LARGE");
    if (!slots.tryAcquire()) throw rejected("PARSER_BUSY");
    Process process = null;
    Path directory = null;
    Future<T> exchange = null;
    String containerName = container == null ? null : "document-parser-" + UUID.randomUUID();
    try {
      directory = Files.createTempDirectory("document-parser-");
      process = start(container == null ? command(directory, heapMb)
          : container.command(containerName, heapMb, timeout), directory);
      active.add(process);
      Process child = process;
      exchange = io.submit(() -> {
        try (var output = new DataOutputStream(new BufferedOutputStream(child.getOutputStream()));
             var input = new DataInputStream(new BufferedInputStream(child.getInputStream()))) {
          output.writeUTF(operation); settings.write(output); output.writeInt(page);
          output.writeInt(bytes.length); output.write(bytes); output.flush();
          int status = input.readUnsignedByte();
          if (status == 1) throw rejected(input.readUTF());
          if (status != 0) throw new IOException("Invalid parser response");
          T result = response.read(input);
          if (input.read() != -1) throw new IOException("Trailing parser response");
          if (child.waitFor() != 0) throw new IOException("Parser exited unsuccessfully");
          return result;
        }
      });
      return exchange.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException expired) {
      throw rejected("PARSER_TIMEOUT");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt(); throw rejected("PARSER_UNAVAILABLE");
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof InvalidDocumentException invalid) throw invalid;
      throw rejected("MALFORMED_FILE");
    } catch (IOException | RejectedExecutionException unavailable) {
      throw rejected("PARSER_UNAVAILABLE");
    } finally {
      boolean releaseSlot = true;
      if (process != null) { terminate(process); active.remove(process); }
      if (exchange != null) exchange.cancel(true);
      try {
        if (containerName != null && directory != null) {
          try { container.remove(containerName, directory); }
          catch (IOException failure) {
            releaseSlot = false; // Do not admit more work while a prior container may still exist.
            throw new IllegalStateException("Document parser container cleanup failed; restart after runtime recovery");
          }
        }
        removeDirectory(directory);
      }
      finally { if (releaseSlot) slots.release(); }
    }
  }

  static Process start(List<String> command, Path directory) throws IOException {
    var builder = new ProcessBuilder(command).directory(directory.toFile())
        .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().clear(); // Includes JAVA_TOOL_OPTIONS, database passwords and encryption keys.
    return builder.start();
  }

  static List<String> command(Path directory, int heapMb) throws IOException {
    return command(directory, heapMb, System.getProperty("java.class.path"));
  }

  static List<String> command(Path directory, int heapMb, String runtimeClasspath) throws IOException {
    var command = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx" + heapMb + "m", "-XX:MaxDirectMemorySize=32m", "-XX:MaxMetaspaceSize=128m",
        "-XX:ReservedCodeCacheSize=64m", "-XX:ActiveProcessorCount=1", "-XX:+ExitOnOutOfMemoryError",
        "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=" + directory.resolve("jvm-error.log"),
        "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + directory, "-Duser.home=" + directory));
    String classpath = Arrays.stream(runtimeClasspath.split(java.io.File.pathSeparator))
        .map(entry -> Path.of(entry).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
    boolean bootJar = false;
    if (!classpath.contains(File.pathSeparator) && classpath.endsWith(".jar")) {
      try (var jar = new java.util.jar.JarFile(classpath)) { bootJar = jar.getEntry("BOOT-INF/classes/") != null; }
    }
    if (bootJar) command.add("-Dloader.main=" + DocumentParserWorker.class.getName());
    command.addAll(List.of("-cp", classpath, bootJar
        ? "org.springframework.boot.loader.launch.PropertiesLauncher" : DocumentParserWorker.class.getName()));
    return command;
  }

  private static void terminate(Process process) {
    var descendants = process.descendants().toList();
    descendants.forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    boolean interrupted = Thread.interrupted();
    try { process.waitFor(2, TimeUnit.SECONDS); }
    catch (InterruptedException stopped) { interrupted = true; }
    finally { if (interrupted) Thread.currentThread().interrupt(); }
  }

  static void removeDirectory(Path directory) {
    if (directory == null) return;
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    } catch (IOException failure) {
      // Do not include paths or document contents in diagnostics.
      throw new IllegalStateException("Document parser temporary-file cleanup failed");
    }
  }

  @jakarta.annotation.PreDestroy
  void close() { active.forEach(DocumentParserProcess::terminate); io.shutdownNow(); }

  static byte[] readBytes(DataInputStream input, int limit) throws IOException {
    int length = bounded(input.readInt(), 0, limit);
    byte[] bytes = input.readNBytes(length);
    if (bytes.length != length) throw new EOFException();
    return bytes;
  }

  static int bounded(int value, int min, int max) throws IOException {
    if (value < min || value > max) throw new IOException("Invalid parser response size");
    return value;
  }
  private static Integer nullablePositive(int value) throws IOException { return value == 0 ? null : bounded(value, 1, 40_000_000); }
  private static String mediaType(String value) throws IOException {
    if (!SUPPORTED_MEDIA_TYPES.contains(value)) throw new IOException("Invalid media type");
    return value;
  }
  static InvalidDocumentException rejected(String code) {
    String safeCode = Set.of("EMPTY_FILE", "FILE_TOO_LARGE", "EXECUTABLE_FILE", "UNSUPPORTED_FILE",
        "ENCRYPTED_FILE", "MALFORMED_FILE", "PAGE_LIMIT_EXCEEDED", "RESOLUTION_TOO_LOW", "PIXEL_LIMIT_EXCEEDED",
        "DECOMPRESSION_RISK", "PARSER_BUSY", "PARSER_TIMEOUT", "PARSER_UNAVAILABLE").contains(code) ? code : "MALFORMED_FILE";
    return DocumentFileInspector.invalid(safeCode, switch (safeCode) {
      case "PARSER_BUSY", "PARSER_UNAVAILABLE" -> "Document processing is temporarily unavailable. Please retry.";
      case "PARSER_TIMEOUT" -> "The document exceeded its processing deadline. Export a simpler copy and retry.";
      case "EMPTY_FILE" -> "The uploaded document is empty. Choose a PDF or image and try again.";
      case "FILE_TOO_LARGE" -> "The document exceeds the file-size limit. Upload a smaller file and try again.";
      case "EXECUTABLE_FILE", "UNSUPPORTED_FILE" -> "This file type is not accepted. Upload a PDF, JPEG, PNG or HEIC document and try again.";
      case "ENCRYPTED_FILE" -> "Password-protected PDFs cannot be processed. Upload an unlocked copy and try again.";
      case "PAGE_LIMIT_EXCEEDED" -> "The PDF exceeds the page limit. Split it into smaller documents and try again.";
      case "RESOLUTION_TOO_LOW" -> "The image resolution is too low. Upload a clearer photo and try again.";
      case "PIXEL_LIMIT_EXCEEDED", "DECOMPRESSION_RISK" -> "The document expands beyond the safe processing limit. Resize or re-export it and retry.";
      default -> "The document could not be decoded safely. Export a new PDF or image and retry.";
    });
  }
  record Page(byte[] png, int width, int height, int dpi) {}
  @FunctionalInterface private interface Response<T> { T read(DataInputStream input) throws Exception; }

  record Settings(int maxFileBytes, int maxPages, long maxPixels, int minWidth, int minHeight,
      long expansionRatio, int dpi, long maxPagePixels, String heicCommand, long decoderTimeoutMillis) {
    Settings {
      if (maxFileBytes < 1 || maxFileBytes > MAX_INPUT_BYTES || maxPages < 1 || maxPages > 25
          || maxPixels < 1 || maxPixels > 40_000_000 || minWidth < 1 || minHeight < 1
          || expansionRatio < 1 || expansionRatio > 10_000 || dpi < 36 || dpi > 300
          || maxPagePixels < 1 || maxPagePixels > 40_000_000 || decoderTimeoutMillis < 1 || decoderTimeoutMillis > 60_000) throw new IllegalArgumentException("Invalid document limits");
    }
    static Settings from(Environment env) {
      return new Settings(env.getProperty("document.limits.max-file-bytes", Integer.class, MAX_INPUT_BYTES),
          env.getProperty("document.limits.max-pages", Integer.class, 25),
          env.getProperty("document.limits.max-pixels", Long.class, 40_000_000L),
          env.getProperty("document.limits.min-width", Integer.class, 1), env.getProperty("document.limits.min-height", Integer.class, 1),
          env.getProperty("document.limits.max-expansion-ratio", Long.class, 10_000L),
          env.getProperty("document.normalization.dpi", Integer.class, 144),
          env.getProperty("document.normalization.max-page-pixels", Long.class, 40_000_000L),
          env.getProperty("document.parser.mode", "process").equals("container")
              ? env.getProperty("document.heic.decoder-command", "heif-dec")
              : executable(env.getProperty("document.heic.decoder-command", "heif-dec")),
          env.getProperty("document.decoder.timeout", Duration.class, Duration.ofSeconds(10)).toMillis());
    }
    private static String executable(String command) {
      if (command.contains(File.separator)) return Path.of(command).toAbsolutePath().toString();
      for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
        Path candidate = Path.of(dir, command);
        if (Files.isExecutable(candidate)) return candidate.toAbsolutePath().toString();
      }
      return command;
    }
    void write(DataOutputStream out) throws IOException {
      out.writeInt(maxFileBytes); out.writeInt(maxPages); out.writeLong(maxPixels);
      out.writeInt(minWidth); out.writeInt(minHeight); out.writeLong(expansionRatio);
      out.writeInt(dpi); out.writeLong(maxPagePixels); out.writeUTF(heicCommand); out.writeLong(decoderTimeoutMillis);
    }
    static Settings read(DataInputStream in) throws IOException {
      return new Settings(in.readInt(), in.readInt(), in.readLong(), in.readInt(), in.readInt(),
          in.readLong(), in.readInt(), in.readLong(), in.readUTF(), in.readLong());
    }
  }
}
