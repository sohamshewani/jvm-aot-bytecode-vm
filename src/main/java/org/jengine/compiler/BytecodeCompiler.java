package org.jengine.compiler;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BytecodeCompiler {

    public static final int OP_NOP       = 0x00;
    public static final int OP_LOAD_CONST= 0x01;
    public static final int OP_LOAD_REG  = 0x02;
    public static final int OP_STORE_REG = 0x03;
    public static final int OP_ADD       = 0x04;
    public static final int OP_SUB       = 0x05;
    public static final int OP_MUL       = 0x06;
    public static final int OP_DIV       = 0x07;
    public static final int OP_JUMP      = 0x08;
    public static final int OP_JUMP_IF   = 0x09;
    public static final int OP_CALL      = 0x0A;
    public static final int OP_RET       = 0x0B;
    public static final int OP_PRINT     = 0x0C;

    private static final int MAX_REGISTERS = 256;

    public interface ASTNode {
        void accept(ASTVisitor visitor);
        int getLine();
    }

    public static final class LiteralNode implements ASTNode {
        private final long nanBoxedValue;
        private final int line;

        public LiteralNode(long nanBoxedValue, int line) {
            this.nanBoxedValue = nanBoxedValue;
            this.line = line;
        }

        public long getNanBoxedValue() {
            return nanBoxedValue;
        }

        @Override
        public void accept(ASTVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public int getLine() {
            return line;
        }
    }

    public static final class VariableNode implements ASTNode {
        private final String name;
        private final int line;

        public VariableNode(String name, int line) {
            this.name = name;
            this.line = line;
        }

        public String getName() {
            return name;
        }

        @Override
        public void accept(ASTVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public int getLine() {
            return line;
        }
    }

    public static final class BinaryNode implements ASTNode {
        private final String operator;
        private final ASTNode left;
        private final ASTNode right;
        private final int line;

        public BinaryNode(String operator, ASTNode left, ASTNode right, int line) {
            this.operator = operator;
            this.left = left;
            this.right = right;
            this.line = line;
        }

        public String getOperator() {
            return operator;
        }

        public ASTNode getLeft() {
            return left;
        }

        public ASTNode getRight() {
            return right;
        }

        @Override
        public void accept(ASTVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public int getLine() {
            return line;
        }
    }

    public static final class AssignNode implements ASTNode {
        private final String name;
        private final ASTNode value;
        private final int line;

        public AssignNode(String name, ASTNode value, int line) {
            this.name = name;
            this.value = value;
            this.line = line;
        }

        public String getName() {
            return name;
        }

        public ASTNode getValue() {
            return value;
        }

        @Override
        public void accept(ASTVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public int getLine() {
            return line;
        }
    }

    public static final class PrintNode implements ASTNode {
        private final ASTNode expression;
        private final int line;

        public PrintNode(ASTNode expression, int line) {
            this.expression = expression;
            this.line = line;
        }

        public ASTNode getExpression() {
            return expression;
        }

        @Override
        public void accept(ASTVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public int getLine() {
            return line;
        }
    }

    public interface ASTVisitor {
        void visit(LiteralNode node);
        void visit(VariableNode node);
        void visit(BinaryNode node);
        void visit(AssignNode node);
        void visit(PrintNode node);
    }

    public static final class CompilationResult {
        private final int[] instructions;
        private final long[] constants;
        private final int instructionCount;

        public CompilationResult(int[] instructions, long[] constants, int instructionCount) {
            this.instructions = instructions;
            this.constants = constants;
            this.instructionCount = instructionCount;
        }

        public int[] getInstructions() {
            return instructions;
        }

        public long[] getConstants() {
            return constants;
        }

        public int getInstructionCount() {
            return instructionCount;
        }

        public ByteBuffer toDirectByteBuffer() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(instructionCount * 4);
            for (int i = 0; i < instructionCount; i++) {
                buffer.putInt(instructions[i]);
            }
            buffer.flip();
            return buffer;
        }
    }

    private final List<Integer> codeBuffer = new ArrayList<>();
    private final List<Long> constantPool = new ArrayList<>();
    private final Map<String, Integer> symbolTable = new HashMap<>();
    
    private int nextRegister = 0;
    private int activeResultRegister = -1;

    public BytecodeCompiler() {
    }

    public CompilationResult compile(List<ASTNode> astNodes) {
        codeBuffer.clear();
        constantPool.clear();
        symbolTable.clear();
        nextRegister = 0;
        activeResultRegister = -1;

        ASTVisitor visitor = new CompilerVisitor();
        for (ASTNode node : astNodes) {
            node.accept(visitor);
        }

        emit(OP_RET, 0, 0, 0);

        int count = codeBuffer.size();
        int[] instructions = new int[count];
        for (int i = 0; i < count; i++) {
            instructions[i] = codeBuffer.get(i);
        }

        long[] constants = new long[constantPool.size()];
        for (int i = 0; i < constantPool.size(); i++) {
            constants[i] = constantPool.get(i);
        }

        return new CompilationResult(instructions, constants, count);
    }

    private void emit(int op, int rA, int rB, int rC) {
        if ((op & ~0xFF) != 0) {
            throw new IllegalArgumentException("Opcode exceeds 8 bits: " + op);
        }
        if ((rA & ~0xFF) != 0 || (rB & ~0xFF) != 0 || (rC & ~0xFF) != 0) {
            throw new IllegalArgumentException("Register operand exceeds 8 bits");
        }
        int word = ((op & 0xFF) << 24) | ((rA & 0xFF) << 16) | ((rB & 0xFF) << 8) | (rC & 0xFF);
        codeBuffer.add(word);
    }

    private int allocateRegister() {
        if (nextRegister >= MAX_REGISTERS) {
            throw new IllegalStateException("Register allocation overflow: exceeded 256 hardware-emulated registers.");
        }
        return nextRegister++;
    }

    private int addConstant(long nanBoxedValue) {
        int index = constantPool.size();
        if (index > 0xFF) {
            throw new IllegalStateException("Constant pool overflow: exceeded 256 entries.");
        }
        constantPool.add(nanBoxedValue);
        return index;
    }

    private int resolveVariable(String name) {
        Integer reg = symbolTable.get(name);
        if (reg == null) {
            int newReg = allocateRegister();
            symbolTable.put(name, newReg);
            return newReg;
        }
        return reg;
    }

    private final class CompilerVisitor implements ASTVisitor {

        @Override
        public void visit(LiteralNode node) {
            int constIdx = addConstant(node.getNanBoxedValue());
            int targetReg = allocateRegister();
            emit(OP_LOAD_CONST, targetReg, constIdx, 0);
            activeResultRegister = targetReg;
        }

        @Override
        public void visit(VariableNode node) {
            int varReg = resolveVariable(node.getName());
            int targetReg = allocateRegister();
            emit(OP_LOAD_REG, targetReg, varReg, 0);
            activeResultRegister = targetReg;
        }

        @Override
        public void visit(BinaryNode node) {
            node.getLeft().accept(this);
            int leftReg = activeResultRegister;

            node.getRight().accept(this);
            int rightReg = activeResultRegister;

            int targetReg = allocateRegister();
            int opCode;
            switch (node.getOperator()) {
                case "+":
                    opCode = OP_ADD;
                    break;
                case "-":
                    opCode = OP_SUB;
                    break;
                case "*":
                    opCode = OP_MUL;
                    break;
                case "/":
                    opCode = OP_DIV;
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported binary operator: " + node.getOperator());
            }

            emit(opCode, targetReg, leftReg, rightReg);
            activeResultRegister = targetReg;
        }

        @Override
        public void visit(AssignNode node) {
            node.getValue().accept(this);
            int valReg = activeResultRegister;

            int varReg = resolveVariable(node.getName());
            emit(OP_STORE_REG, varReg, valReg, 0);
            activeResultRegister = varReg;
        }

        @Override
        public void visit(PrintNode node) {
            node.getExpression().accept(this);
            int exprReg = activeResultRegister;
            emit(OP_PRINT, exprReg, 0, 0);
            activeResultRegister = exprReg;
        }
    }
}