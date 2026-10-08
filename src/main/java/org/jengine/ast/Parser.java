package org.jengine.ast;

import java.util.ArrayList;
import java.util.List;

public final class Parser {

    public static final byte TOKEN_EOF = 0;
    public static final byte TOKEN_IDENTIFIER = 1;
    public static final byte TOKEN_NUMBER = 2;
    public static final byte TOKEN_STRING = 3;
    public static final byte TOKEN_LET = 4;
    public static final byte TOKEN_FN = 5;
    public static final byte TOKEN_RETURN = 6;
    public static final byte TOKEN_IF = 7;
    public static final byte TOKEN_ELSE = 8;
    public static final byte TOKEN_WHILE = 9;
    public static final byte TOKEN_PLUS = 10;
    public static final byte TOKEN_MINUS = 11;
    public static final byte TOKEN_MULTIPLY = 12;
    public static final byte TOKEN_DIVIDE = 13;
    public static final byte TOKEN_ASSIGN = 14;
    public static final byte TOKEN_EQ = 15;
    public static final byte TOKEN_NEQ = 16;
    public static final byte TOKEN_LT = 17;
    public static final byte TOKEN_LTE = 18;
    public static final byte TOKEN_GT = 19;
    public static final byte TOKEN_GTE = 20;
    public static final byte TOKEN_LPAREN = 21;
    public static final byte TOKEN_RPAREN = 22;
    public static final byte TOKEN_LBRACE = 23;
    public static final byte TOKEN_RBRACE = 24;
    public static final byte TOKEN_COMMA = 25;
    public static final byte TOKEN_SEMICOLON = 26;

    public static final int PREC_LOWEST = 0;
    public static final int PREC_ASSIGN = 1;
    public static final int PREC_EQUALS = 2;
    public static final int PREC_LESSGREATER = 3;
    public static final int PREC_SUM = 4;
    public static final int PREC_PRODUCT = 5;
    public static final int PREC_PREFIX = 6;
    public static final int PREC_CALL = 7;

    public static final class TokenStream {
        private final byte[] sourceBuffer;
        private final int[] tokenTypes;
        private final int[] tokenStarts;
        private final int[] tokenLengths;
        private int cursor;
        private final int tokenCount;

        public TokenStream(byte[] sourceBuffer, int[] tokenTypes, int[] tokenStarts, int[] tokenLengths, int tokenCount) {
            this.sourceBuffer = sourceBuffer;
            this.tokenTypes = tokenTypes;
            this.tokenStarts = tokenStarts;
            this.tokenLengths = tokenLengths;
            this.tokenCount = tokenCount;
            this.cursor = 0;
        }

        public int peekType() {
            if (cursor >= tokenCount) {
                return TOKEN_EOF;
            }
            return tokenTypes[cursor];
        }

        public int consumeType() {
            if (cursor >= tokenCount) {
                return TOKEN_EOF;
            }
            return tokenTypes[cursor++];
        }

        public void consume() {
            if (cursor < tokenCount) {
                cursor++;
            }
        }

        public boolean match(int expectedType) {
            if (peekType() == expectedType) {
                consume();
                return true;
            }
            return false;
        }

        public void expect(int expectedType) {
            if (consumeType() != expectedType) {
                throw new ParseException("Expected token type " + expectedType + " at position " + (cursor > 0 ? tokenStarts[cursor - 1] : 0));
            }
        }

        public String lexeme(int index) {
            int start = tokenStarts[index];
            int len = tokenLengths[index];
            return new String(sourceBuffer, start, len, java.nio.charset.StandardCharsets.UTF_8);
        }

        public String currentLexeme() {
            if (cursor >= tokenCount) {
                return "";
            }
            return lexeme(cursor);
        }

        public int currentIndex() {
            return cursor;
        }
    }

    public static class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }
    }

    public interface ASTNode {
        <R> R accept(ASTVisitor<R> visitor);
    }

    public interface ASTVisitor<R> {
        R visitProgram(ProgramNode node);
        R visitVarDecl(VarDeclNode node);
        R visitFnDecl(FnDeclNode node);
        R visitReturn(ReturnNode node);
        R visitIf(IfNode node);
        R visitWhile(WhileNode node);
        R visitBlock(BlockNode node);
        R visitExpressionStmt(ExpressionStmtNode node);
        R visitAssign(AssignNode node);
        R visitBinary(BinaryNode node);
        R visitIdentifier(IdentifierNode node);
        R visitNumberLiteral(NumberLiteralNode node);
        R visitStringLiteral(StringLiteralNode node);
        R visitCall(CallNode node);
    }

    public static final class ProgramNode implements ASTNode {
        public final List<ASTNode> statements;

        public ProgramNode(List<ASTNode> statements) {
            this.statements = statements;
        }

        @Override
        public <R> R accept(ASTVisitor<R> visitor) {
            return visitor.visitProgram(this);
        }
    }

    public static final class VarDeclNode implements ASTNode {
        public final String name;
        public final ASTNode initializer;

        public VarDeclNode(String name, ASTNode initializer) {
            this.name = name;
            this.initializer = initializer;
        }

        @Override
        public <R> R accept(ASTVisitor<R> visitor) {
            return visitor.visitVarDecl(this);
        }
    }

    public static final class FnDeclNode implements ASTNode {
        public final String name;
        public final List<String> parameters;
        public final BlockNode body;

        public FnDeclNode(String name, List<String> parameters, BlockNode body) {
            this.name = name;
            this.parameters = parameters;
            this.body = body;
        }

        @Override
        public <R> R accept(ASTVisitor<R> visitor) {
            return visitor.visitFnDecl(this);
        }
    }

    public static final class ReturnNode implements ASTNode {
        public final ASTNode value;

        public ReturnNode(ASTNode value) {
            this.value = value;
        }

        @Override
        public <R> R accept(ASTVisitor<R> visitor) {
            return visitor.visitReturn(this);
        }
    }

    public static final class IfNode implements ASTNode {
        public final ASTNode condition;
        public final ASTNode thenBranch;
        public final ASTNode elseBranch;

        public IfNode(ASTNode condition, ASTNode thenBranch, ASTNode elseBranch) {
            this.condition = condition;
            this.thenBranch = thenBranch;
            this.elseBranch = elseBranch;
        }

        @Override
        public <R> R accept(ASTVisitor<R> visitor) {
            return visitor.visitIf(this);
        }
    }

    public static final class WhileNode implements ASTNode {
        public final ASTNode condition;
        public final ASTNode body;

        public WhileNode(ASTNode condition, ASTNode body) {
            this.condition = condition;
            this.body = body;
        }

        @Override
        public <R> R accept(ASTVisitor<R> visitor) {
            return visitor.visitWhile(this);
        }
    }

    public static final class BlockNode implements ASTNode {
        public final List<ASTNode> statements;

        public BlockNode(List<ASTNode> statements) {
            this.statements = statements;
        }

        @Override
        public <R> R accept(ASTVisitor<R> visitor) {
            return visitor.visitBlock(this);
        }
    }

    public static final class ExpressionStmtNode implements ASTNode {
        public final ASTNode expression;

        public ExpressionStmtNode(ASTNode expression) {
            this.expression = expression;
        }

        @Override
        public <R> R accept(ASTVisitor<R> visitor) {
            return visitor.visitExpressionStmt(this);
        }
    }

    public static final class AssignNode implements ASTNode {
        public final String name;
        public final ASTNode value;

        public AssignNode(String name, ASTNode value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public <R> R accept(ASTVisitor<R> visitor) {
            return visitor.visitAssign(this);
        }
    }

    public static final class BinaryNode implements ASTNode {
        public final ASTNode left;
        public final int operator;
        public final ASTNode right;

        public BinaryNode(ASTNode left, int operator, ASTNode right) {
            this.left = left;
            this.operator = operator;
            this.right = right;
        }

        @Override
        public <R> R accept(ASTVisitor<R> visitor) {
            return visitor.visitBinary(this);
        }
    }

    public static final class IdentifierNode implements ASTNode {
        public final String name;

        public IdentifierNode(String name) {
            this.name = name;
        }

        @Override
        public <R> R accept(ASTVisitor<R> visitor) {
            return visitor.visitIdentifier(this);
        }
    }

    public static final class NumberLiteralNode implements ASTNode {
        public final double value;

        public NumberLiteralNode(double value) {
            this.value = value;
        }

        @Override
        public <R> R accept(ASTVisitor<R> visitor) {
            return visitor.visitNumberLiteral(this);
        }
    }

    public static final class StringLiteralNode implements ASTNode {
        public final String value;

        public StringLiteralNode(String value) {
            this.value = value;
        }

        @Override
        public <R> R accept(ASTVisitor<R> visitor) {
            return visitor.visitStringLiteral(this);
        }
    }

    public static final class CallNode implements ASTNode {
        public final ASTNode callee;
        public final List<ASTNode> arguments;

        public CallNode(ASTNode callee, List<ASTNode> arguments) {
            this.callee = callee;
            this.arguments = arguments;
        }

        @Override
        public <R> R accept(ASTVisitor<R> visitor) {
            return visitor.visitCall(this);
        }
    }

    private final TokenStream tokens;

    public Parser(TokenStream tokens) {
        this.tokens = tokens;
    }

    public ProgramNode parseProgram() {
        List<ASTNode> statements = new ArrayList<>();
        while (tokens.peekType() != TOKEN_EOF) {
            statements.add(parseStatement());
        }
        return new ProgramNode(statements);
    }

    private ASTNode parseStatement() {
        int type = tokens.peekType();
        switch (type) {
            case TOKEN_LET:
                return parseVarDecl();
            case TOKEN_FN:
                return parseFnDecl();
            case TOKEN_RETURN:
                return parseReturn();
            case TOKEN_IF:
                return parseIf();
            case TOKEN_WHILE:
                return parseWhile();
            case TOKEN_LBRACE:
                return parseBlock();
            default:
                return parseExpressionStmt();
        }
    }

    private ASTNode parseVarDecl() {
        tokens.consume(); // consume 'let'
        if (tokens.peekType() != TOKEN_IDENTIFIER) {
            throw new ParseException("Expected identifier after 'let'");
        }
        String name = tokens.currentLexeme();
        tokens.consume();

        tokens.expect(TOKEN_ASSIGN);
        ASTNode initializer = parseExpression(PREC_LOWEST);
        tokens.match(TOKEN_SEMICOLON);
        return new VarDeclNode(name, initializer);
    }

    private ASTNode parseFnDecl() {
        tokens.consume(); // consume 'fn'
        if (tokens.peekType() != TOKEN_IDENTIFIER) {
            throw new ParseException("Expected function name after 'fn'");
        }
        String name = tokens.currentLexeme();
        tokens.consume();

        tokens.expect(TOKEN_LPAREN);
        List<String> parameters = new ArrayList<>();
        if (tokens.peekType() != TOKEN_RPAREN) {
            do {
                if (tokens.peekType() != TOKEN_IDENTIFIER) {
                    throw new ParseException("Expected parameter identifier");
                }
                parameters.add(tokens.currentLexeme());
                tokens.consume();
            } while (tokens.match(TOKEN_COMMA));
        }
        tokens.expect(TOKEN_RPAREN);
        tokens.expect(TOKEN_LBRACE);
        BlockNode body = (BlockNode) parseBlockBody();
        return new FnDeclNode(name, parameters, body);
    }

    private ASTNode parseReturn() {
        tokens.consume(); // consume 'return'
        ASTNode value = null;
        if (tokens.peekType() != TOKEN_SEMICOLON && tokens.peekType() != TOKEN_RBRACE && tokens.peekType() != TOKEN_EOF) {
            value = parseExpression(PREC_LOWEST);
        }
        tokens.match(TOKEN_SEMICOLON);
        return new ReturnNode(value);
    }

    private ASTNode parseIf() {
        tokens.consume(); // consume 'if'
        tokens.expect(TOKEN_LPAREN);
        ASTNode condition = parseExpression(PREC_LOWEST);
        tokens.expect(TOKEN_RPAREN);
        ASTNode thenBranch = parseStatement();
        ASTNode elseBranch = null;
        if (tokens.match(TOKEN_ELSE)) {
            elseBranch = parseStatement();
        }
        return new IfNode(condition, thenBranch, elseBranch);
    }

    private ASTNode parseWhile() {
        tokens.consume(); // consume 'while'
        tokens.expect(TOKEN_LPAREN);
        ASTNode condition = parseExpression(PREC_LOWEST);
        tokens.expect(TOKEN_RPAREN);
        ASTNode body = parseStatement();
        return new WhileNode(condition, body);
    }

    private ASTNode parseBlock() {
        tokens.consume(); // consume '{'
        List<ASTNode> statements = new ArrayList<>();
        while (tokens.peekType() != TOKEN_RBRACE && tokens.peekType() != TOKEN_EOF) {
            statements.add(parseStatement());
        }
        tokens.expect(TOKEN_RBRACE);
        return new BlockNode(statements);
    }

    private ASTNode parseBlockBody() {
        List<ASTNode> statements = new ArrayList<>();
        while (tokens.peekType() != TOKEN_RBRACE && tokens.peekType() != TOKEN_EOF) {
            statements.add(parseStatement());
        }
        tokens.expect(TOKEN_RBRACE);
        return new BlockNode(statements);
    }

    private ASTNode parseExpressionStmt() {
        ASTNode expr = parseExpression(PREC_LOWEST);
        tokens.match(TOKEN_SEMICOLON);
        return new ExpressionStmtNode(expr);
    }

    private ASTNode parseExpression(int precedence) {
        int tokenType = tokens.peekType();
        ASTNode left = parsePrefix(tokenType);

        while (precedence < getPrecedence(tokens.peekType())) {
            int infixType = tokens.peekType();
            left = parseInfix(infixType, left);
        }

        return left;
    }

    private int getPrecedence(int tokenType) {
        switch (tokenType) {
            case TOKEN_ASSIGN:
                return PREC_ASSIGN;
            case TOKEN_EQ:
            case TOKEN_NEQ:
                return PREC_EQUALS;
            case TOKEN_LT:
            case TOKEN_LTE:
            case TOKEN_GT:
            case TOKEN_GTE:
                return PREC_LESSGREATER;
            case TOKEN_PLUS:
            case TOKEN_MINUS:
                return PREC_SUM;
            case TOKEN_MULTIPLY:
            case TOKEN_DIVIDE:
                return PREC_PRODUCT;
            case TOKEN_LPAREN:
                return PREC_CALL;
            default:
                return PREC_LOWEST;
        }
    }

    private ASTNode parsePrefix(int tokenType) {
        switch (tokenType) {
            case TOKEN_IDENTIFIER: {
                String name = tokens.currentLexeme();
                tokens.consume();
                return new IdentifierNode(name);