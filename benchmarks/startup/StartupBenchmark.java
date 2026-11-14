import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/** The same standard Java ProcessBuilder workload runs on a Host or Espresso JVM. */
public final class StartupBenchmark {
    private static final String USAGE =
            "Usage: StartupBenchmark [host-process|guest-process] [iterations]";

    private StartupBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--noop-child")) return;
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println(USAGE);
            return;
        }
        try {
            Options options = options(args);
            boolean espresso = System.getProperty("java.vm.name", "")
                    .toLowerCase(Locale.ROOT).contains("espresso");
            if (options.mode().equals("host-process") && espresso) {
                throw new UsageFailure("host-process must be run outside Espresso in a host JVM.");
            }
            if (options.mode().equals("guest-process") && !espresso) {
                throw new UsageFailure("guest-process must be run inside Espressh in an Espresso Guest JVM.");
            }
            run(options, espresso);
        } catch (UsageFailure failure) {
            System.err.println(failure.getMessage());
            System.exit(2);
        }
    }

    private record Options(String mode, int iterations) {}

    private static Options options(String[] args) {
        String mode = "auto";
        int index = 0;
        if (args.length > 0 && (args[0].equals("host-process") || args[0].equals("guest-process"))) {
            mode = args[0];
            index++;
        }
        if (args.length > index + 1) throw new UsageFailure(USAGE);
        int iterations = 30;
        if (args.length > index) {
            try {
                iterations = Integer.parseInt(args[index]);
            } catch (NumberFormatException failure) {
                throw new UsageFailure("Iterations must be a positive integer.\n" + USAGE);
            }
        }
        if (iterations <= 0) throw new UsageFailure("Iterations must be a positive integer.");
        return new Options(mode, iterations);
    }

    private static void run(Options options, boolean espresso) throws Exception {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        String classpath = System.getProperty("java.class.path");
        String environment = espresso ? "espresso-vm" : "host-jvm";
        System.out.println("ProcessBuilder Startup Benchmark");
        System.out.println("Environment: " + environment + "; requested mode: " + options.mode()
                + "; iterations: " + options.iterations());
        System.out.println("VM: " + System.getProperty("java.vm.name")
                + "; Java: " + System.getProperty("java.version"));
        System.out.println("Java home: " + System.getProperty("java.home"));
        System.out.println("Classpath: " + classpath);
        System.out.println("Child: " + java + " -cp <same classpath> StartupBenchmark --noop-child");
        System.out.println("Scope: ProcessBuilder launch, no-op entry, stream closure, and confirmed termination.");
        System.out.println("VM-name labels are descriptive; they do not establish backend identity or authority.");
        System.out.println("Top-level startup is excluded. First invocation is not a cold-start guarantee.");

        double[] samples = new double[options.iterations()];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = sample(java, classpath);
            int number = index + 1;
            if (number <= 5 || number % 10 == 0 || number == samples.length) {
                System.out.printf(Locale.ROOT, "  [processbuilder] #%d/%d: %.3f ms%n",
                        number, samples.length, samples[index]);
            }
        }
        summary(samples);
        System.out.println("BENCHMARK_COMPLETE environment=" + environment + " samples=" + samples.length);
    }

    private static double sample(String java, String classpath) throws Exception {
        long started = System.nanoTime();
        Process child = new ProcessBuilder(java, "-cp", classpath,
                "StartupBenchmark", "--noop-child").redirectErrorStream(true).start();
        Throwable failure = null;
        try {
            child.getOutputStream().close();
            byte[] diagnostics;
            try (var output = child.getInputStream()) {
                diagnostics = output.readAllBytes();
            }
            int status = child.waitFor();
            if (status != 0) {
                throw new IOException("Child exited with status " + status + ": "
                        + new String(diagnostics, StandardCharsets.UTF_8));
            }
        } catch (Exception | Error problem) {
            failure = problem;
            throw problem;
        } finally {
            try {
                cleanup(child);
            } catch (Exception | Error cleanupFailure) {
                if (failure == null) throw cleanupFailure;
                failure.addSuppressed(cleanupFailure);
            }
        }
        return (System.nanoTime() - started) / 1_000_000.0;
    }

    private static void cleanup(Process child) throws IOException {
        boolean interrupted = false;
        try {
            if (child.isAlive()) child.destroyForcibly();
            for (;;) {
                try {
                    child.waitFor();
                    break;
                } catch (InterruptedException interruption) {
                    interrupted = true;
                    // An interrupted benchmark must not leave its timed child running.
                    child.destroyForcibly();
                }
            }
        } finally {
            try {
                closeAll(child.getOutputStream(), child.getInputStream(), child.getErrorStream());
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeAll(Closeable... streams) throws IOException {
        IOException failure = null;
        for (Closeable stream : streams) {
            try {
                stream.close();
            } catch (IOException problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        if (failure != null) throw failure;
    }

    private static void summary(double[] samples) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        double total = 0, later = 0;
        for (int index = 0; index < samples.length; index++) {
            total += samples[index];
            if (index >= 5) later += samples[index];
        }
        System.out.println("Summary: ProcessBuilder launch through cleanup");
        System.out.printf(Locale.ROOT, "  Total: %.3f ms; first invocation: %.3f ms; mean: %.3f ms%n",
                total, samples[0], total / samples.length);
        if (samples.length > 5) {
            System.out.printf(Locale.ROOT, "  Mean excluding first five: %.3f ms%n",
                    later / (samples.length - 5));
        } else {
            System.out.println("  Mean excluding first five: unavailable (requires at least 6 samples)");
        }
        System.out.printf(Locale.ROOT, "  Min / P50 / P95 / Max: %.3f / %.3f / %.3f / %.3f ms%n",
                sorted[0], sorted[(int) Math.ceil(sorted.length * 0.50) - 1],
                sorted[(int) Math.ceil(sorted.length * 0.95) - 1], sorted[sorted.length - 1]);
    }

    private static final class UsageFailure extends RuntimeException {
        UsageFailure(String message) { super(message); }
    }
}
