package com.framehub.video.media;

import com.framehub.video.config.VideoProperties;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class ProcessRunner {
  private final VideoProperties p;

  public ProcessRunner(VideoProperties p) {
    this.p = p;
  }

  public void run(List<String> command, Path dir, String name, Instant deadline) throws Exception {
    ProcessingBudget.check();
    deadline = ProcessingBudget.cap(deadline);
    Path budgetRoot = dir.toAbsolutePath().normalize();
    Path configuredRoot = p.workDir().toAbsolutePath().normalize();
    while (budgetRoot.getParent() != null
        && budgetRoot.getParent().startsWith(configuredRoot)
        && !budgetRoot.getParent().equals(configuredRoot)) budgetRoot = budgetRoot.getParent();
    Path out = dir.resolve(name + ".stdout"), error = dir.resolve(name + ".stderr");
    Process process =
        new ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectOutput(out.toFile())
            .redirectError(error.toFile())
            .start();
    try {
      while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
        if (Thread.currentThread().isInterrupted() || Instant.now().isAfter(deadline))
          throw new ProcessFailure("Media process deadline exceeded");
        if (bytes(budgetRoot) > p.maxTempBytes())
          throw new ProcessFailure("Temporary disk budget exceeded");
      }
      if (process.exitValue() != 0) {
        org.slf4j.LoggerFactory.getLogger(ProcessRunner.class)
            .warn("Media process {} exited {}: {}", name, process.exitValue(), tail(error));
        throw new ProcessFailure("Media process failed with exit code " + process.exitValue());
      }
    } finally {
      terminate(process);
    }
  }

  private static void terminate(Process process) {
    boolean interrupted = Thread.interrupted();
    try {
      List<ProcessHandle> handles = new ArrayList<>(process.descendants().toList());
      Collections.reverse(handles);
      handles.add(process.toHandle());
      handles.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      for (ProcessHandle handle : handles) {
        while (handle.isAlive()) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) throw new ProcessFailure("Media process did not terminate within cleanup deadline");
          try { handle.onExit().get(remaining, TimeUnit.NANOSECONDS); }
          catch (InterruptedException e) { interrupted = true; }
          catch (java.util.concurrent.TimeoutException e) { throw new ProcessFailure("Media child process cleanup timed out"); }
          catch (java.util.concurrent.ExecutionException e) { throw new ProcessFailure("Media child process cleanup failed"); }
        }
      }
      try { process.getOutputStream().close(); } catch (IOException ignored) { }
      try { process.getInputStream().close(); } catch (IOException ignored) { }
      try { process.getErrorStream().close(); } catch (IOException ignored) { }
    } finally { if (interrupted) Thread.currentThread().interrupt(); }
  }

  static String tail(Path file) throws IOException {
    try (var input = new RandomAccessFile(file.toFile(), "r")) {
      input.seek(Math.max(0, input.length() - 4096));
      byte[] b = new byte[(int) Math.min(4096, input.length())];
      input.readFully(b);
      return new String(b, java.nio.charset.StandardCharsets.UTF_8).replaceAll("[\\r\\n]", " ");
    }
  }

  public static long bytes(Path dir) throws IOException {
    try (var files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .mapToLong(
              f -> {
                try {
                  return Files.size(f);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              })
          .sum();
    }
  }

  public static class ProcessFailure extends RuntimeException {
    public ProcessFailure(String message) {
      super(message);
    }
  }
}
