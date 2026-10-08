package org.jengine.vm;

import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RegisterVirtualMachine {
    private static final int REGISTERS_COUNT = 256;
    private static final int STACK_SIZE = 65536;

    private final long[] registers;
    private final long[] stack;
    private int stackPointer;
    private int instructionPointer;
    
    private final AtomicBoolean safepointRequested;
    private final VarHandle stackPointerHandle;

    public RegisterVirtualMachine() {
        this.registers = new long[REGISTERS_COUNT];
        this.stack = new long[STACK_SIZE];
        this.stackPointer = 0;
        this.instructionPointer = 0;
        this.safepointRequested = new AtomicBoolean(false);
        
        try {
            this.stackPointerHandle = java.lang.invoke.MethodHandles.lookup()
                .findVarHandle(RegisterVirtualMachine.class, "stackPointer", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to initialize VarHandle for RegisterVirtualMachine", e);
        }
    }

    public void execute(int[] bytecode) {
        if (bytecode == null) {
            throw new IllegalArgumentException("Bytecode instruction stream cannot be null");
        }

        final int length = bytecode.length;
        while (instructionPointer < length) {
            if (safepointRequested.get()) {
                handleSafepoint();
            }

            int instruction = bytecode[instructionPointer++];
            int opcode = (instruction >>> 24) & 0xFF;
            int rA = (instruction >>> 16) & 0xFF;
            int rB = (instruction >>> 8) & 0xFF;
            int rC = instruction & 0xFF;

            switch (opcode) {
                case 0x01: // MOV reg, reg
                    registers[rA] = registers[rB];
                    break;
                case 0x02: // ADD reg, reg, reg
                    verifyType(registers[rB], 0x7FF8000000000000L); // Check for int/float NaN box tag
                    verifyType(registers[rC], 0x7FF8000000000000L);
                    registers[rA] = addValues(registers[rB], registers[rC]);
                    break;
                case 0x03: // SUB reg, reg, reg
                    registers[rA] = subValues(registers[rB], registers[rC]);
                    break;
                case 0x04: // MUL reg, reg, reg
                    registers[rA] = mulValues(registers[rB], registers[rC]);
                    break;
                case 0x05: // PUSH reg
                    if (stackPointer >= STACK_SIZE) {
                        throw new StackOverflowError("VM execution stack overflow exceeded limit: " + STACK_SIZE);
                    }
                    stack[stackPointer++] = registers[rA];
                    break;
                case 0x06: // POP reg
                    if (stackPointer <= 0) {
                        throw new IllegalStateException("VM execution stack underflow");
                    }
                    registers[rA] = stack[--stackPointer];
                    break;
                case 0x00: // HALT
                    return;
                default:
                    throw new UnsupportedOperationException("Unknown opcode encountered: 0x" + Integer.toHexString(opcode));
            }
        }
    }

    private void verifyType(long value, long expectedTagMask) {
        // Strict NaN-boxed tag verification runtime check
        long tag = value & 0xFFF8000000000000L;
        if ((tag & 0x7FF0000000000000L) == 0x7FF0000000000000L) {
            if (tag != expectedTagMask) {
                throw new ClassCastException("Type mismatch: Expected NaN-box tag 0xLong, found 0x" + Long.toHexString(tag));
            }
        }
    }

    private long addValues(long a, long b) {
        double da = Double.longBitsToDouble(a);
        double db = Double.longBitsToDouble(b);
        return Double.doubleToRawLongBits(da + db);
    }

    private long subValues(long a, long b) {
        double da = Double.longBitsToDouble(a);
        double db = Double.longBitsToDouble(b);
        return Double.doubleToRawLongBits(da - db);
    }

    private long mulValues(long a, long b) {
        double da = Double.longBitsToDouble(a);
        double db = Double.longBitsToDouble(b);
        return Double.doubleToRawLongBits(da * db);
    }

    public void requestSafepoint() {
        safepointRequested.set(true);
    }

    private void handleSafepoint() {
        synchronized (this) {
            // Synchronize execution context roots during stop-the-world (STW) phase
            safepointRequested.set(false);
        }
    }

    public long[] getRegisters() {
        return registers;
    }

    public long[] getStack() {
        return stack;
    }

    public int getStackPointer() {
        return (int) stackPointerHandle.get(this);
    }

    public int getInstructionPointer() {
        return instructionPointer;
    }
}