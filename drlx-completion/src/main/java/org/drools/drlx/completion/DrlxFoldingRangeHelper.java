package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.drools.drlx.parser.DrlxParser.DrlxCompilationUnitContext;
import org.drools.drlx.parser.DrlxParser.ImportDeclarationContext;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;

public final class DrlxFoldingRangeHelper {

    private DrlxFoldingRangeHelper() {
    }

    /**
     * Returns the folding ranges for {@code text}, or an empty list.
     */
    public static List<FoldingRange> foldingRanges(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        try {
            DrlxLexer lexer = new DrlxLexer(CharStreams.fromString(text));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            DrlxParser parser = new DrlxParser(tokens);
            ParseTree parseTree = parser.drlxStart();
            if (parseTree == null || isJavaStyleCompilationUnit(parseTree)) {
                return Collections.emptyList();
            }

            List<FoldingRange> ranges = new ArrayList<>();

            // 1. Regions from document symbols (rules, etc.)
            for (DocumentSymbol symbol : DrlxDocumentSymbolHelper.symbols(text)) {
                int start = symbol.getRange().getStart().getLine();
                int end = symbol.getRange().getEnd().getLine();
                if (end > start) {
                    FoldingRange r = new FoldingRange(start, end);
                    r.setKind(FoldingRangeKind.Region);
                    ranges.add(r);
                }
            }

            // 2. Multi-line imports group
            DrlxCompilationUnitContext cu = findCompilationUnit(parseTree);
            if (cu != null && cu.importDeclaration() != null && !cu.importDeclaration().isEmpty()) {
                List<ImportDeclarationContext> imports = cu.importDeclaration();
                int firstStart = imports.get(0).getStart().getLine() - 1;
                int lastEnd = imports.get(imports.size() - 1).getStop().getLine() - 1;
                if (lastEnd > firstStart) {
                    FoldingRange r = new FoldingRange(firstStart, lastEnd);
                    r.setKind(FoldingRangeKind.Imports);
                    ranges.add(r);
                }
            }

            // 3. Comments (block comments and contiguous line comments)
            tokens.fill();
            List<Token> allTokens = tokens.getTokens();
            int lineCommentStart = -1;
            int prevLineCommentLine = -1;

            for (Token t : allTokens) {
                int type = t.getType();
                if (type == DrlxLexer.COMMENT) {
                    // Flush any pending line comments
                    if (lineCommentStart != -1 && prevLineCommentLine > lineCommentStart) {
                        FoldingRange r = new FoldingRange(lineCommentStart, prevLineCommentLine);
                        r.setKind(FoldingRangeKind.Comment);
                        ranges.add(r);
                    }
                    lineCommentStart = -1;
                    prevLineCommentLine = -1;

                    // Block comment
                    int startLine = t.getLine() - 1;
                    int endLine = getStopLine(t);
                    if (endLine > startLine) {
                        FoldingRange r = new FoldingRange(startLine, endLine);
                        r.setKind(FoldingRangeKind.Comment);
                        ranges.add(r);
                    }
                } else if (type == DrlxLexer.LINE_COMMENT) {
                    int curLine = t.getLine() - 1;
                    if (lineCommentStart == -1) {
                        lineCommentStart = curLine;
                        prevLineCommentLine = curLine;
                    } else if (curLine == prevLineCommentLine + 1) {
                        prevLineCommentLine = curLine;
                    } else {
                        if (prevLineCommentLine > lineCommentStart) {
                            FoldingRange r = new FoldingRange(lineCommentStart, prevLineCommentLine);
                            r.setKind(FoldingRangeKind.Comment);
                            ranges.add(r);
                        }
                        lineCommentStart = curLine;
                        prevLineCommentLine = curLine;
                    }
                } else if (type != DrlxLexer.WS && type != Token.EOF) {
                    // Non-whitespace, non-comment token: flush line comments
                    if (lineCommentStart != -1 && prevLineCommentLine > lineCommentStart) {
                        FoldingRange r = new FoldingRange(lineCommentStart, prevLineCommentLine);
                        r.setKind(FoldingRangeKind.Comment);
                        ranges.add(r);
                    }
                    lineCommentStart = -1;
                    prevLineCommentLine = -1;
                }
            }

            // Flush remaining line comments at end of file
            if (lineCommentStart != -1 && prevLineCommentLine > lineCommentStart) {
                FoldingRange r = new FoldingRange(lineCommentStart, prevLineCommentLine);
                r.setKind(FoldingRangeKind.Comment);
                ranges.add(r);
            }

            return ranges;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static int getStopLine(Token t) {
        String text = t.getText();
        if (text == null || text.isEmpty()) {
            return t.getLine() - 1;
        }
        int line = t.getLine() - 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static DrlxCompilationUnitContext findCompilationUnit(ParseTree node) {
        if (node instanceof DrlxCompilationUnitContext cu) return cu;
        for (int i = 0; i < node.getChildCount(); i++) {
            DrlxCompilationUnitContext found = findCompilationUnit(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isJavaStyleCompilationUnit(ParseTree node) {
        if (node instanceof DrlxParser.CompilationUnitContext) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (isJavaStyleCompilationUnit(node.getChild(i))) return true;
        }
        return false;
    }
}
