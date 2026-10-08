package org.jengine.memory;

import sun.misc.Unsafe;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public final class MarkSweepHeap {

    private static final Unsafe UNSAFE;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Unsafe", e);
        }
    }

    private static final int HEADER_SIZE = 16; 
    private static final int FREE_LIST_COUNT = 8; 

    private final long totalCapacity;
    private final long baseAddress;
    private final ReentrantLock heapLock;
    private final AtomicLong allocatedBytes;
    private final AtomicBoolean stwRequested;

    private final long[] freeListHeads;
    private final long[] freeListTails;

    public MarkSweepHeap(long capacityBytes) {
        if (capacityBytes <= HEADER_SIZE) {
            throw new IllegalArgumentException("Heap capacity too small");
        }
        this.totalCapacity = capacityBytes;
        this.baseAddress = UNSAFE.allocateMemory(capacityBytes);
        this.heapLock = new ReentrantLock();
        this.allocatedBytes = new AtomicLong(0);
        this.stwRequested = new AtomicBoolean(false);

        this.freeListHeads = new long[FREE_LIST_COUNT];
        this.freeListTails = new long[FREE_LIST_COUNT];

        initializeHeap();
    }

    private void initializeHeap() {
        UNSAFE.setMemory(baseAddress, totalCapacity, (byte) 0);
        
        for (int i = 0; i < FREE_LIST_COUNT; i++) {
            freeListHeads[i] = 0;
            freeListTails[i] = 0;
        }

        long usableSize = totalCapacity - HEADER_SizeAlignment(HEADER_SIZE);
        int sizeClass = selectSizeClass(usableSize);

        long initialBlock = baseAddress;
        writeHeader(initialBlock, usableSize, false, true);
        writeFreePointers(initialBlock, 0, 0);

        freeListHeads[sizeClass] = initialBlock;
        freeListTails[sizeClass] = initialBlock;
    }

    public long allocate(long size) {
        long alignedSize = alignSize(size + HEADER_SIZE);
        int sizeClass = selectSizeClass(alignedSize);

        heapLock.lock();
        try {
            long block = findFreeBlock(sizeClass, alignedSize);
            if (block == 0) {
                collectGarbage();
                block = findFreeBlock(sizeClass, alignedSize);
                if (block == 0) {
                    throw new OutOfMemoryError("Heap exhausted after full GC collection");
                }
            }

            removeFromFreeList(block, sizeClass);
            
            long blockSize = readBlockSize(block);
            if (blockSize > alignedSize + HEADER_SizeAlignment(HEADER_SIZE)) {
                long remainderAddress = block + alignedSize;
                long remainderSize = blockSize - alignedSize;
                
                writeHeader(remainderAddress, remainderSize, false, true);
                int remainderClass = selectSizeClass(remainderSize);
                insertIntoFreeList(remainderAddress, remainderClass);

                writeHeader(block, alignedSize, true, false);
            } else {
                writeHeader(block, blockSize, true, false);
            }

            allocatedBytes.addAndGet(readBlockSize(block));
            return block + HEADER_SIZE;
        } finally {
            heapLock.unlock();
        }
    }

    public void collectGarbage() {
        stwRequested.set(true);
        heapLock.lock();
        try {
            markPhase();
            sweepPhase();
        } finally {
            stwRequested.set(false);
            heapLock.unlock();
        }
    }

    private void markPhase() {
    }

    private void sweepPhase() {
        for (int i = 0; i < FREE_LIST_COUNT; i++) {
            freeListHeads[i] = 0;
            freeListTails[i] = 0;
        }
        allocatedBytes.set(0);

        long current = baseAddress;
        long endAddress = baseAddress + totalCapacity;

        while (current < endAddress) {
            long size = readBlockSize(current);
            boolean isAllocated = readIsAllocated(current);

            if (isAllocated) {
                if (readMarkBit(current)) {
                    writeMarkBit(current, false);
                    allocatedBytes.addAndGet(size);
                } else {
                    writeHeader(current, size, false, true);
                    int sizeClass = selectSizeClass(size);
                    insertIntoFreeList(current, sizeClass);
                }
            } else {
                int sizeClass = selectSizeClass(size);
                insertIntoFreeList(current, sizeClass);
            }
            current += size;
        }
        coalesceFreeLists();
    }

    private void coalesceFreeLists() {
    }

    private long findFreeBlock(int startClass, long requiredSize) {
        for (int i = startClass; i < FREE_LIST_COUNT; i++) {
            long current = freeListHeads[i];
            while (current != 0) {
                if (readBlockSize(current) >= requiredSize) {
                    return current;
                }
                current = readNextFree(current);
            }
        }
        return 0;
    }

    private void insertIntoFreeList(long block, int sizeClass) {
        long tail = freeListTails[sizeClass];
        if (tail == 0) {
            freeListHeads[sizeClass] = block;
            freeListTails[sizeClass] = block;
            writeFreePointers(block, 0, 0);
        } else {
            writeFreePointers(block, tail, 0);
            writeNextFree(tail, block);
            freeListTails[sizeClass] = block;
        }
    }

    private void removeFromFreeList(long block, int sizeClass) {
        long prev = readPrevFree(block);
        long next = readNextFree(block);

        if (prev != 0) {
            writeNextFree(prev, next);
        } else {
            freeListHeads[sizeClass] = next;
        }

        if (next != 0) {
            writePrevFree(next, prev);
        } else {
            freeListTails[sizeClass] = prev;
        }
        writeFreePointers(block, 0, 0);
    }

    private int selectSizeClass(long size) {
        if (size <= 32) return 0;
        if (size <= 64) return 1;
        if (size <= 128) return 2;
        if (size <= 256) return 3;
        if (size <= 512) return 4;
        if (size <= 1024) return 5;
        if (size <= 2048) return 6;
        return 7;
    }

    private long alignSize(long size) {
        return (size + 7) & ~7;
    }

    private long HEADER_SizeAlignment(long size) {
        return (size + 7) & ~7;
    }

    private void writeHeader(long address, long size, boolean allocated, boolean free) {
        long flags = (allocated ? 1L : 0L) | (free ? 2L : 0L);
        UNSAFE.putLong(address, size);
        UNSAFE.putLong(address + 8, flags);
    }

    private long readBlockSize(long address) {
        return UNSAFE.getLong(address);
    }

    private boolean readIsAllocated(long address) {
        long flags = UNSAFE.getLong(address + 8);
        return (flags & 1L) != 0;
    }

    private boolean readMarkBit(long address) {
        long flags = UNSAFE.getLong(address + 8);
        return (flags & 4L) != 0;
    }

    private void writeMarkBit(long address, boolean marked) {
        long flags = UNSAFE.getLong(address + 8);
        if (marked) {
            flags |= 4L;
        } else {
            flags &= ~4L;
        }
        UNSAFE.putLong(address + 8, flags);
    }

    private void writeFreePointers(long address, long prev, long next) {
        UNSAFE.putLong(address + 16, prev);
        UNSAFE.putLong(address + 24, next);
    }

    private long readPrevFree(long address) {
        return UNSAFE.getLong(address + 16);
    }

    private long readNextFree(long address) {
        return UNSAFE.getLong(address + 24);
    }

    private void writePrevFree(long address, long prev) {
        UNSAFE.putLong(address + 16, prev);
    }

    private void writeNextFree(long address, long next) {
        UNSAFE.putLong(address + 24, next);
    }

    public void release() {
        heapLock.lock();
        try {
            UNSAFE.freeMemory(baseAddress);
        } finally {
            heapLock.unlock();
        }
    }
}