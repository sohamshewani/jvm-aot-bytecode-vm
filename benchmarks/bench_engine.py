import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class JvmAotBytecodeVmBenchmark {

    // Dummy AOT bytecode VM simulation context and instruction stream
    static final class AotVmContext {
        int accumulator = 0;
        int pc = 0;
        final int[] bytecode = {
            1, 10, // ADD 10
            2, 2,  // MUL 2
            1, 5,  // ADD 5
            3      // HALT / RESET
        };

        void executeStep() {
            if (pc >= bytecode.length) {
                pc = 0;
            }
            int op = bytecode[pc++];
            int arg = bytecode[pc++];
            switch (op) {
                case 1: accumulator += arg; break;
                case 2: accumulator *= arg; break;
                case 3: accumulator = 0; pc = 0; break;
                default: accumulator ^= arg; break;
            }
        }
    }

    public static void main(String[] args) {
        int warmupIterations = 500_000;
        int measurementIterations = 5_000_000;
        
        AotVmContext vm = new AotVmContext();

        System.out.println("Starting Warmup (" + warmupIterations + " ops)...");
        for (int i = 0; i < warmupIterations; i++) {
            vm.executeStep();
        }

        System.out.println("Starting Measurement (" + measurementIterations + " ops)...");
        
        long[] latencies = new long[measurementIterations];
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        
        // Ensure CPU time measurement is supported
        if (!threadMXBean.isCurrentThreadCpuTimeSupported()) {
            System.err.println("Thread CPU time is not supported!");
            return;
        }

        long totalStartTime = System.nanoTime();

        for (int i = 0; i < measurementIterations; i++) {
            long startNano = threadMXBean.getCurrentThreadCpuTime();
            
            vm.executeStep();
            
            long endNano = threadMXBean.getCurrentThreadCpuTime();
            latencies[i] = Math.max(1L, endNano - startNano);
        }

        long totalEndTime = System.nanoTime();
        long totalDurationNs = totalEndTime - totalStartTime;

        // Sort latencies for percentile calculation
        Arrays.sort(latencies);

        double opsPerSec = (measurementIterations * 1_000_000_000.0) / totalDurationNs;
        long p50 = latencies[measurementIterations / 2];
        long p99 = latencies[(int) (measurementIterations * 0.99)];
        long p999 = latencies[(int) (measurementIterations * 0.999)];
        long max = latencies[measurementIterations - 1];

        // Format and print results
        System.out.println("\n============================================================");
        System.out.println("          jvm-aot-bytecode-vm Microbenchmark Results        ");
        System.out.println("============================================================");
        System.out.printf("%-25s | %-20s%n", "Metric", "Value");
        System.out.println("------------------------------------------------------------");
        System.out.printf("%-25s | %d%n", "Total Iterations", measurementIterations);
        System.out.printf("%-25s | %.2f ms%n", "Total Duration", totalDurationNs / 1_000_000.0);
        System.out.printf("%-25s | %,.2f ops/sec%n", "Throughput", opsPerSec);
        System.out.println("------------------------------------------------------------");
        System.out.printf("%-25s | %d ns%n", "Latency p50 (Median)", p50);
        System.out.printf("%-25s | %d ns%n", "Latency p99", p99);
        System.out.printf("%-25s | %d ns%n", "Latency p99.9", p999);
        System.out.printf("%-25s | %d ns%n", "Latency Max", max);
        System.out.println("============================================================");
        
        // Prevent dead-code elimination of 'vm' state
        if (vm.accumulator == -9999) {
            System.out.println(vm.accumulator);
        }
    }
}