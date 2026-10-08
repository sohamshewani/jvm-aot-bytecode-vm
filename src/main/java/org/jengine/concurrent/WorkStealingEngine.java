package org.jengine.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public final class WorkStealingEngine {

    private final int workerCount;
    private final ForkJoinPool pool;
    private final AtomicInteger activeWorkers;
    private final GlobalRunQueue globalQueue;
    private volatile boolean isShutdown;

    public WorkStealingEngine(int workerCount) {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("Worker count must be strictly positive.");
        }
        this.workerCount = workerCount;
        this.activeWorkers = new AtomicInteger(0);
        this.globalQueue = new GlobalRunQueue();
        this.isShutdown = false;

        this.pool = new ForkJoinPool(
            workerCount,
            new VMWorkerThreadFactory(),
            null,
            true
        );
    }

    public void execute(Runnable task) {
        if (isShutdown) {
            throw new IllegalStateException("WorkStealingEngine is shut down.");
        }
        VMTask wrappedTask = new VMTask(task);
        globalQueue.push(wrappedTask);
        pool.submit(new WorkStealingAction(this, wrappedTask));
    }

    public void shutdown() {
        this.isShutdown = true;
        pool.shutdown();
    }

    public boolean isShutdown() {
        return isShutdown;
    }

    public int getActiveWorkerCount() {
        return activeWorkers.get();
    }

    public int getWorkerCount() {
        return workerCount;
    }

    protected void incrementActiveWorkers() {
        activeWorkers.incrementAndGet();
    }

    protected void decrementActiveWorkers() {
        activeWorkers.decrementAndGet();
    }

    protected VMTask pollGlobalQueue() {
        return globalQueue.pop();
    }

    private static final class VMWorkerThreadFactory implements ForkJoinPool.ForkJoinWorkerThreadFactory {
        @Override
        public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return new VMWorkerThread(pool);
        }
    }

    private static final class VMWorkerThread extends ForkJoinWorkerThread {
        protected VMWorkerThread(ForkJoinPool pool) {
            super(pool);
            setDaemon(false);
            setName("jengine-worker-" + pool.toString());
        }
    }

    public static final class VMTask extends RecursiveAction {
        private final Runnable delegate;
        private volatile int state; // 0: PENDING, 1: RUNNING, 2: COMPLETED, 3: FAILED
        private static final VarHandle STATE_HANDLE;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                STATE_HANDLE = l.findVarHandle(VMTask.class, "state", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        public VMTask(Runnable delegate) {
            this.delegate = delegate;
            this.state = 0;
        }

        @Override
        protected void compute() {
            if (!STATE_HANDLE.compareAndSet(this, 0, 1)) {
                return;
            }
            try {
                delegate.run();
                STATE_HANDLE.setRelease(this, 2);
            } catch (Throwable t) {
                STATE_HANDLE.setRelease(this, 3);
                if (t instanceof ThreadDeath) {
                    throw (ThreadDeath) t;
                }
                Thread.currentThread().getUncaughtExceptionHandler()
                    .uncaughtException(Thread.currentThread(), t);
            }
        }

        public boolean isCompleted() {
            return (int) STATE_HANDLE.getAcquire(this) == 2;
        }

        public boolean isFailed() {
            return (int) STATE_HANDLE.getAcquire(this) == 3;
        }
    }

    private static final class WorkStealingAction extends RecursiveAction {
        private final WorkStealingEngine engine;
        private final VMTask initialTask;

        private WorkStealingAction(WorkStealingEngine engine, VMTask initialTask) {
            this.engine = engine;
            this.initialTask = initialTask;
        }

        @Override
        protected void compute() {
            engine.incrementActiveWorkers();
            try {
                if (initialTask != null && !initialTask.isDone()) {
                    initialTask.compute();
                }

                while (!engine.isShutdown()) {
                    VMTask task = engine.pollGlobalQueue();
                    if (task == null) {
                        task = (VMTask) ForkJoinTask.pollExecutorTask();
                    }

                    if (task != null) {
                        task.compute();
                    } else {
                        // Backoff to prevent busy spinning under CPU starvation
                        LockSupport.parkNanos(1000L);
                        if (Thread.currentThread().isInterrupted()) {
                            break;
                        }
                    }
                }
            } finally {
                engine.decrementActiveWorkers();
            }
        }
    }

    private static final class GlobalRunQueue {
        private static final int INITIAL_CAPACITY = 1024;
        private volatile VMTask[] queue;
        private volatile int head;
        private volatile int tail;

        private static final VarHandle QUEUE_HANDLE;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                QUEUE_HANDLE = l.findVarHandle(GlobalRunQueue.class, "queue", VMTask[].class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        public GlobalRunQueue() {
            this.queue = new VMTask[INITIAL_CAPACITY];
            this.head = 0;
            this.tail = 0;
        }

        public synchronized void push(VMTask task) {
            int h = head;
            int t = tail;
            VMTask[] q = queue;
            
            if (t - h >= q.length - 1) {
                int oldCapacity = q.length;
                int newCapacity = oldCapacity * 2;
                VMTask[] newQueue = new VMTask[newCapacity];
                for (int i = 0; i < oldCapacity; i++) {
                    newQueue[i] = q[(h + i) & (oldCapacity - 1)];
                }
                queue = newQueue;
                q = newQueue;
                head = 0;
                tail = oldCapacity;
                h = 0;
                t = oldCapacity;
            }

            q[t & (q.length - 1)] = task;
            tail = t + 1;
        }

        public synchronized VMTask pop() {
            VMTask[] q = queue;
            int h = head;
            int t = tail;
            if (h == t) {
                return null;
            }

            int mask = q.length - 1;
            VMTask task = q[h & mask];
            if (task == null) {
                return null;
            }

            q[h & mask] = null;
            head = h + 1;
            return task;
        }
    }
}