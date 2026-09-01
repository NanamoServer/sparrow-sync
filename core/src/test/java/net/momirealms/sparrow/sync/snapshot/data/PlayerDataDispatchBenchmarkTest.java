package net.momirealms.sparrow.sync.snapshot.data;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 测量轻量玩家数据任务的直接调用、单次线程交接与并行 fan-out 成本.
 * 报告只描述本机 CPU 负载下的调度交叉点, 不作为生产性能门槛.
 */
class PlayerDataDispatchBenchmarkTest {
    private static final int POOL_PARALLELISM = 16;
    private static final int[] TASK_COUNTS = {2, 4, 8};
    private static final long[] TARGET_PAYLOAD_NANOS = {
            0L, 500L, 1_000L, 2_000L, 4_000L, 8_000L,
            16_000L, 32_000L, 64_000L, 128_000L, 256_000L, 512_000L
    };
    private static final int DIRECT_BATCH_SIZE = 1_000_000;
    private static final int DIRECT_WARMUP_BATCHES = 5;
    private static final int DIRECT_SAMPLES = 15;
    private static final int HANDOFF_WARMUPS = 1_000;
    private static final int HANDOFF_SAMPLES = 1_001;
    private static final int CALIBRATION_ITERATIONS = 200_000;
    private static final int CALIBRATION_SAMPLES = 11;
    private static final int PAYLOAD_SAMPLES = 21;
    private static final int FAN_OUT_WARMUPS = 6;
    private static final int FAN_OUT_SAMPLES = 31;
    private static final double CONFIRMED_WIN_RATIO = 1.05;

    private static volatile long blackhole;

    @Test
    void benchmarkDispatchCrossover() throws IOException {
        long benchmarkStart = System.nanoTime();
        StringBuilder report = new StringBuilder();
        try (ForkJoinPool pool = new ForkJoinPool(POOL_PARALLELISM)) {
            warmPool(pool);
            Environment environment = environment(pool);
            DirectResult direct = measureDirectCalls();
            HandoffResult handoff = measureHandoff(pool);
            Calibration calibration = calibratePayload();
            List<FanOutResult> fanOut = measureFanOut(pool, calibration);

            appendEnvironment(report, environment);
            appendMethod(report, calibration);
            report.append(String.format(Locale.ROOT, "%ndirect inlined trivial Runnable body: %.3f ns/call (%d calls/sample, median of %d)%n",
                    direct.nanosPerCall(), DIRECT_BATCH_SIZE, DIRECT_SAMPLES));
            report.append(String.format(Locale.ROOT, "warmed execute + completion wait: %.3f us/handoff (median of %d)%n",
                    nanosToMicros(handoff.medianNanos()), HANDOFF_SAMPLES));
            appendFanOut(report, fanOut);
            appendCrossovers(report, fanOut);
        }
        report.append(String.format(Locale.ROOT, "%ntotal benchmark wall time: %.3f ms%n", nanosToMillis(System.nanoTime() - benchmarkStart)));

        Path reportPath = Path.of("build", "player-data-dispatch-benchmark.txt").toAbsolutePath();
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);
        System.out.print(report);
        System.out.println("report: " + reportPath);
    }

    private static DirectResult measureDirectCalls() {
        CountingRunnable task = new CountingRunnable();
        for (int i = 0; i < DIRECT_WARMUP_BATCHES; i++) {
            runDirectBatch(task);
        }
        long[] samples = new long[DIRECT_SAMPLES];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            runDirectBatch(task);
            samples[i] = System.nanoTime() - start;
        }
        assertEquals((long) (DIRECT_WARMUP_BATCHES + DIRECT_SAMPLES) * DIRECT_BATCH_SIZE, task.executions);
        blackhole = task.value;
        return new DirectResult(median(samples) / (double) DIRECT_BATCH_SIZE);
    }

    private static void runDirectBatch(Runnable task) {
        for (int i = 0; i < DIRECT_BATCH_SIZE; i++) {
            task.run();
        }
    }

    private static HandoffResult measureHandoff(ForkJoinPool pool) {
        AtomicLong executions = new AtomicLong();
        for (int i = 0; i < HANDOFF_WARMUPS; i++) {
            handoff(pool, executions);
        }
        long[] samples = new long[HANDOFF_SAMPLES];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            handoff(pool, executions);
            samples[i] = System.nanoTime() - start;
        }
        assertEquals(HANDOFF_WARMUPS + HANDOFF_SAMPLES, executions.get());
        return new HandoffResult(median(samples));
    }

    private static void handoff(ForkJoinPool pool, AtomicLong executions) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        pool.execute(() -> {
            executions.incrementAndGet();
            completion.complete(null);
        });
        completion.join();
    }

    private static Calibration calibratePayload() {
        for (int i = 0; i < 8; i++) {
            blackhole = cpuPayload(CALIBRATION_ITERATIONS, i + 1L);
        }
        long[] samples = new long[CALIBRATION_SAMPLES];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            blackhole = cpuPayload(CALIBRATION_ITERATIONS, i + 17L);
            samples[i] = System.nanoTime() - start;
        }
        return new Calibration(median(samples) / (double) CALIBRATION_ITERATIONS);
    }

    private static List<FanOutResult> measureFanOut(ForkJoinPool pool, Calibration calibration) {
        List<FanOutResult> results = new ArrayList<>(TASK_COUNTS.length * TARGET_PAYLOAD_NANOS.length);
        for (int taskIndex = 0; taskIndex < TASK_COUNTS.length; taskIndex++) {
            int taskCount = TASK_COUNTS[taskIndex];
            for (int payloadIndex = 0; payloadIndex < TARGET_PAYLOAD_NANOS.length; payloadIndex++) {
                long targetNanos = TARGET_PAYLOAD_NANOS[payloadIndex];
                int iterations = calibration.iterationsFor(targetNanos);
                long payloadNanos = measurePayload(iterations);
                long expected = expectedChecksum(taskCount, iterations);
                for (int i = 0; i < FAN_OUT_WARMUPS; i++) {
                    assertEquals(expected, runSerial(taskCount, iterations).checksum());
                    assertEquals(expected, runParallel(pool, taskCount, iterations).checksum());
                }
                long[] serialSamples = new long[FAN_OUT_SAMPLES];
                long[] parallelSamples = new long[FAN_OUT_SAMPLES];
                for (int i = 0; i < FAN_OUT_SAMPLES; i++) {
                    if ((i & 1) == 0) {
                        recordSerial(serialSamples, i, expected, taskCount, iterations);
                        recordParallel(parallelSamples, i, expected, pool, taskCount, iterations);
                    } else {
                        recordParallel(parallelSamples, i, expected, pool, taskCount, iterations);
                        recordSerial(serialSamples, i, expected, taskCount, iterations);
                    }
                }
                results.add(new FanOutResult(taskCount, targetNanos, payloadNanos, iterations,
                        median(serialSamples), median(parallelSamples)));
            }
        }
        return results;
    }

    private static void recordSerial(long[] samples, int index, long expected, int taskCount, int iterations) {
        Measurement measurement = runSerial(taskCount, iterations);
        samples[index] = measurement.nanos();
        assertEquals(expected, measurement.checksum());
    }

    private static void recordParallel(long[] samples, int index, long expected, ForkJoinPool pool, int taskCount, int iterations) {
        Measurement measurement = runParallel(pool, taskCount, iterations);
        samples[index] = measurement.nanos();
        assertEquals(expected, measurement.checksum());
    }

    private static long measurePayload(int iterations) {
        long[] samples = new long[PAYLOAD_SAMPLES];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            blackhole = cpuPayload(iterations, i + 31L);
            samples[i] = System.nanoTime() - start;
        }
        return median(samples);
    }

    private static long expectedChecksum(int taskCount, int iterations) {
        long checksum = 0;
        for (int i = 0; i < taskCount; i++) {
            checksum += cpuPayload(iterations, seed(i));
        }
        return checksum;
    }

    private static Measurement runSerial(int taskCount, int iterations) {
        long start = System.nanoTime();
        long checksum = 0;
        for (int i = 0; i < taskCount; i++) {
            checksum += cpuPayload(iterations, seed(i));
        }
        return new Measurement(System.nanoTime() - start, checksum);
    }

    private static Measurement runParallel(ForkJoinPool pool, int taskCount, int iterations) {
        long start = System.nanoTime();
        long[] outputs = new long[taskCount];
        CompletableFuture<?>[] futures = new CompletableFuture<?>[taskCount];
        for (int i = 0; i < taskCount; i++) {
            int taskIndex = i;
            futures[i] = CompletableFuture.runAsync(() -> outputs[taskIndex] = cpuPayload(iterations, seed(taskIndex)), pool);
        }
        CompletableFuture.allOf(futures).join();
        long checksum = 0;
        for (int i = 0; i < outputs.length; i++) {
            checksum += outputs[i];
        }
        return new Measurement(System.nanoTime() - start, checksum);
    }

    private static void warmPool(ForkJoinPool pool) {
        for (int round = 0; round < 8; round++) {
            long[] outputs = new long[POOL_PARALLELISM];
            CompletableFuture<?>[] futures = new CompletableFuture<?>[POOL_PARALLELISM];
            for (int i = 0; i < futures.length; i++) {
                int taskIndex = i;
                futures[i] = CompletableFuture.runAsync(() -> outputs[taskIndex] = cpuPayload(50_000, seed(taskIndex)), pool);
            }
            CompletableFuture.allOf(futures).join();
            blackhole = Arrays.stream(outputs).sum();
        }
    }

    private static long cpuPayload(int iterations, long seed) {
        long value = seed;
        for (int i = 0; i < iterations; i++) {
            value ^= value << 13;
            value ^= value >>> 7;
            value ^= value << 17;
            value += 0x9E3779B97F4A7C15L + i;
        }
        return value;
    }

    private static long seed(int taskIndex) {
        return 0xD1B54A32D192ED03L ^ ((long) taskIndex * 0x9E3779B97F4A7C15L);
    }

    private static long median(long[] samples) {
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    private static Environment environment(ForkJoinPool pool) {
        String processor = System.getenv("PROCESSOR_IDENTIFIER");
        if (processor == null || processor.isBlank()) {
            processor = "unavailable";
        }
        return new Environment(
                System.getProperty("java.runtime.version"),
                System.getProperty("java.vm.name"),
                System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"),
                processor,
                Runtime.getRuntime().availableProcessors(),
                pool.getParallelism(),
                pool.getPoolSize()
        );
    }

    private static void appendEnvironment(StringBuilder report, Environment environment) {
        report.append("Player data dispatch benchmark\n");
        report.append("JDK: ").append(environment.javaVersion()).append(" / ").append(environment.vm()).append('\n');
        report.append("OS: ").append(environment.os()).append('\n');
        report.append("CPU: ").append(environment.processor()).append('\n');
        report.append("available processors: ").append(environment.availableProcessors()).append('\n');
        report.append("ForkJoinPool: parallelism=").append(environment.poolParallelism())
                .append(", warmed pool size=").append(environment.warmedPoolSize()).append('\n');
    }

    private static void appendMethod(StringBuilder report, Calibration calibration) {
        report.append("\nmethod:\n");
        report.append("- deterministic integer mixing is calibrated once, then the observed standalone median is reported for every payload\n");
        report.append("- serial time is a direct loop; parallel time includes future allocation, ForkJoinPool submission, fan-out and allOf completion\n");
        report.append("- serial and parallel samples alternate order; each result is the median of ").append(FAN_OUT_SAMPLES).append(" samples\n");
        report.append("- a confirmed win means at least 5% faster parallel time at two consecutive payload levels\n");
        report.append("- this is not JMH; CPU frequency, OS scheduling, pool contention and nanoTime resolution can move the numbers\n");
        report.append("- the payload models CPU-bound work only; it does not model allocation, locks, cache misses, Bukkit or NMS access\n");
        report.append(String.format(Locale.ROOT, "- calibrated CPU payload: %.4f ns/iteration%n", calibration.nanosPerIteration()));
    }

    private static void appendFanOut(StringBuilder report, List<FanOutResult> results) {
        report.append("\nfan-out medians:\n");
        report.append(String.format(Locale.ROOT, "%5s %10s %11s %10s %12s %13s %9s%n",
                "tasks", "target_us", "payload_us", "iterations", "serial_us", "parallel_us", "speedup"));
        int size = results.size();
        for (int i = 0; i < size; i++) {
            FanOutResult result = results.get(i);
            report.append(String.format(Locale.ROOT, "%5d %10.3f %11.3f %10d %12.3f %13.3f %8.3fx%n",
                    result.taskCount(), nanosToMicros(result.targetNanos()), nanosToMicros(result.payloadNanos()), result.iterations(),
                    nanosToMicros(result.serialNanos()), nanosToMicros(result.parallelNanos()), result.speedup()));
        }
    }

    private static void appendCrossovers(StringBuilder report, List<FanOutResult> results) {
        report.append("\nfirst two-level confirmed parallel wins:\n");
        for (int i = 0; i < TASK_COUNTS.length; i++) {
            int taskCount = TASK_COUNTS[i];
            WinBracket bracket = findConfirmedWin(results, taskCount);
            if (bracket == null) {
                report.append(taskCount).append(" tasks: none in tested range\n");
                continue;
            }
            FanOutResult previous = bracket.previous();
            FanOutResult confirmed = bracket.confirmed();
            report.append(String.format(Locale.ROOT, "%d tasks: crossover between %.3f and %.3f us/task; conservative point %.3f us/task, serial %.3f us, parallel %.3f us, %.3fx%n",
                    taskCount, nanosToMicros(previous.payloadNanos()), nanosToMicros(confirmed.payloadNanos()),
                    nanosToMicros(confirmed.payloadNanos()), nanosToMicros(confirmed.serialNanos()),
                    nanosToMicros(confirmed.parallelNanos()), confirmed.speedup()));
        }
    }

    private static WinBracket findConfirmedWin(List<FanOutResult> results, int taskCount) {
        FanOutResult beforePrevious = null;
        FanOutResult previous = null;
        int size = results.size();
        for (int i = 0; i < size; i++) {
            FanOutResult result = results.get(i);
            if (result.taskCount() != taskCount) {
                continue;
            }
            if (beforePrevious != null && previous.speedup() >= CONFIRMED_WIN_RATIO && result.speedup() >= CONFIRMED_WIN_RATIO) {
                return new WinBracket(beforePrevious, previous);
            }
            beforePrevious = previous;
            previous = result;
        }
        return null;
    }

    private static double nanosToMicros(long nanos) {
        return nanos / 1_000.0;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private record Environment(
            String javaVersion,
            String vm,
            String os,
            String processor,
            int availableProcessors,
            int poolParallelism,
            int warmedPoolSize
    ) {
    }

    private record DirectResult(double nanosPerCall) {
    }

    private record HandoffResult(long medianNanos) {
    }

    private record Calibration(double nanosPerIteration) {
        private int iterationsFor(long targetNanos) {
            if (targetNanos == 0) {
                return 0;
            }
            return Math.max(1, (int) Math.round(targetNanos / this.nanosPerIteration));
        }
    }

    private record Measurement(long nanos, long checksum) {
    }

    private record FanOutResult(
            int taskCount,
            long targetNanos,
            long payloadNanos,
            int iterations,
            long serialNanos,
            long parallelNanos
    ) {
        private double speedup() {
            return this.serialNanos / (double) this.parallelNanos;
        }
    }

    private record WinBracket(FanOutResult previous, FanOutResult confirmed) {
    }

    private static final class CountingRunnable implements Runnable {
        private long executions;
        private long value = 0x94D049BB133111EBL;

        @Override
        public void run() {
            this.executions++;
            this.value ^= this.value << 13;
            this.value ^= this.value >>> 7;
            this.value ^= this.value << 17;
            this.value += this.executions;
        }
    }
}
