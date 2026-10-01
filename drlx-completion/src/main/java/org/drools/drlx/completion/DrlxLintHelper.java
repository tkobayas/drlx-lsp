package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.drools.drlx.parser.DrlxParser;
import org.drools.drlx.parser.DrlxParser.BoundOopathContext;
import org.drools.drlx.parser.DrlxParser.DrlxCompilationUnitContext;
import org.drools.drlx.parser.DrlxParser.ImportDeclarationContext;
import org.drools.drlx.parser.DrlxParser.LocalVariableDeclarationContext;
import org.drools.drlx.parser.DrlxParser.RuleParameterContext;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public final class DrlxLintHelper {

    private static final int MAX_DIAGNOSTICS = 20;
    private static final int MAX_UNKNOWN_TYPE_DIAGNOSTICS = 50;

    private static final Pattern NEW_TYPE_PATTERN =
            Pattern.compile("\\bnew\\s+([A-Z][A-Za-z0-9_$.]*)\\s*\\(");

    private DrlxLintHelper() {
    }

    public static List<Diagnostic> lint(String text) {
        return lint(text, null);
    }

    public static List<Diagnostic> lint(String text, WorkspaceSemanticModel model) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<Diagnostic> result = new ArrayList<>();

        DiagnosticSeverity bracketSeverity = resolveSeverity("drlx.lsp.lint.unbalancedOopathBrackets", "warning");
        String sanitized = sanitize(text);
        if (bracketSeverity != null) {
            result.addAll(lintUnbalancedOopathBrackets(sanitized, bracketSeverity));
        }

        if (model != null && model.isClasspathResolved()) {
            DiagnosticSeverity unknownTypeSeverity = resolveSeverity("drlx.lsp.lint.unknownTypes", "warning");
            if (unknownTypeSeverity != null) {
                result.addAll(lintUnknownTypes(text, sanitized, model, unknownTypeSeverity));
            }
        }

        return result;
    }

    // ---- configuration ------------------------------------------------

    private static DiagnosticSeverity resolveSeverity(String propertyName, String defaultValue) {
        String val = System.getProperty(propertyName, defaultValue)
                .trim().toLowerCase();
        return switch (val) {
            case "off"     -> null;
            case "hint"    -> DiagnosticSeverity.Hint;
            case "info"    -> DiagnosticSeverity.Information;
            case "error"   -> DiagnosticSeverity.Error;
            default        -> DiagnosticSeverity.Warning;
        };
    }

    // ---- sanitize -------------------------------------------------------

    /**
     * Replaces comment and string-literal content with spaces (preserving
     * newlines) so that '['/']' inside those regions cannot affect bracket
     * depth counting.
     */
    static String sanitize(String text) {
        char[] src = text.toCharArray();
        char[] out = src.clone();
        int i = 0;
        int len = src.length;
        while (i < len) {
            // block comment
            if (i + 1 < len && src[i] == '/' && src[i + 1] == '*') {
                out[i] = ' '; out[i + 1] = ' ';
                i += 2;
                while (i < len) {
                    if (i + 1 < len && src[i] == '*' && src[i + 1] == '/') {
                        out[i] = ' '; out[i + 1] = ' ';
                        i += 2;
                        break;
                    }
                    if (src[i] != '\n' && src[i] != '\r') {
                        out[i] = ' ';
                    }
                    i++;
                }
            // line comment
            } else if (i + 1 < len && src[i] == '/' && src[i + 1] == '/') {
                out[i] = ' '; out[i + 1] = ' ';
                i += 2;
                while (i < len && src[i] != '\n' && src[i] != '\r') {
                    out[i] = ' ';
                    i++;
                }
            // string literal
            } else if (src[i] == '"') {
                out[i] = '"';
                i++;
                while (i < len && src[i] != '"' && src[i] != '\n' && src[i] != '\r') {
                    if (src[i] == '\\' && i + 1 < len) {
                        out[i] = ' '; i++;
                        out[i] = ' '; i++;
                    } else {
                        out[i] = ' ';
                        i++;
                    }
                }
                if (i < len && src[i] == '"') {
                    out[i] = '"';
                    i++;
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    // ---- lint pass ------------------------------------------------------

    private static List<Diagnostic> lintUnbalancedOopathBrackets(
            String sanitized, DiagnosticSeverity severity) {

        List<Diagnostic> result = new ArrayList<>();
        String[] lines = sanitized.split("\n", -1);

        enum State { OUTSIDE_RULE, IN_PATTERN, IN_CONSEQUENCE }
        State state = State.OUTSIDE_RULE;

        // Track rule-body brace depth to find the closing '}'
        int ruleBraceDepth = 0;

        // Per-pattern-item bracket tracking
        int bracketDepth = 0;
        // openStack: int[] {line, col} for each unmatched '['
        List<int[]> openStack = new ArrayList<>();

        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx];

            if (state == State.OUTSIDE_RULE) {
                if (line.matches("\\s*rule\\b.*")) {
                    state = State.IN_PATTERN;
                    ruleBraceDepth = 0;
                    bracketDepth = 0;
                    openStack.clear();
                    // Count any '{' on the rule-keyword line
                    for (char c : line.toCharArray()) {
                        if (c == '{') ruleBraceDepth++;
                        else if (c == '}') ruleBraceDepth--;
                    }
                }
                continue;
            }

            // IN_PATTERN or IN_CONSEQUENCE: scan character by character
            char[] chars = line.toCharArray();

            if (state == State.IN_PATTERN) {
                // Check for 'do' keyword that starts consequence
                if (line.matches(".*\\bdo\\b.*")) {
                    // Flush any unclosed '[' before entering consequence
                    for (int[] pos : openStack) {
                        if (result.size() < MAX_DIAGNOSTICS) {
                            result.add(makeDiag(pos[0], pos[1], pos[1] + 1, severity,
                                    "Unclosed '[' in OOPath filter — missing ']'"));
                        }
                    }
                    bracketDepth = 0;
                    openStack.clear();
                    state = State.IN_CONSEQUENCE;
                    // Fall through to track brace depth on this line
                }
            }

            // Track rule-body brace depth in all non-OUTSIDE states
            for (int col = 0; col < chars.length; col++) {
                char c = chars[col];

                if (state == State.IN_PATTERN) {
                    if (c == '[') {
                        bracketDepth++;
                        openStack.add(new int[]{lineIdx, col});
                    } else if (c == ']') {
                        if (bracketDepth > 0) {
                            bracketDepth--;
                            openStack.remove(openStack.size() - 1);
                        } else {
                            if (result.size() < MAX_DIAGNOSTICS) {
                                result.add(makeDiag(lineIdx, col, col + 1, severity,
                                        "Unmatched ']' — no corresponding '['"));
                            }
                        }
                    } else if (c == ',' && bracketDepth == 0) {
                        // End of one pattern item — flush unclosed '['
                        for (int[] pos : openStack) {
                            if (result.size() < MAX_DIAGNOSTICS) {
                                result.add(makeDiag(pos[0], pos[1], pos[1] + 1, severity,
                                        "Unclosed '[' in OOPath filter — missing ']'"));
                            }
                        }
                        bracketDepth = 0;
                        openStack.clear();
                    }
                }

                // Track rule brace depth (in both IN_PATTERN and IN_CONSEQUENCE)
                if (c == '{') {
                    ruleBraceDepth++;
                } else if (c == '}') {
                    ruleBraceDepth--;
                    if (ruleBraceDepth <= 0) {
                        // Rule body closed — flush remaining unclosed '[' if in pattern
                        if (state == State.IN_PATTERN) {
                            for (int[] pos : openStack) {
                                if (result.size() < MAX_DIAGNOSTICS) {
                                    result.add(makeDiag(pos[0], pos[1], pos[1] + 1, severity,
                                            "Unclosed '[' in OOPath filter — missing ']'"));
                                }
                            }
                        }
                        bracketDepth = 0;
                        openStack.clear();
                        state = State.OUTSIDE_RULE;
                        break; // done with this line
                    }
                }
            }
        }
        return result;
    }

    private static Diagnostic makeDiag(int line, int startCol, int endCol,
                                        DiagnosticSeverity severity, String message) {
        Diagnostic d = new Diagnostic();
        d.setRange(new Range(new Position(line, startCol), new Position(line, endCol)));
        d.setSeverity(severity);
        d.setSource("drlx-lint");
        d.setMessage(message);
        return d;
    }

    // ---- unknown type lint pass -----------------------------------------

    private static List<Diagnostic> lintUnknownTypes(
            String text, String sanitized, WorkspaceSemanticModel model, DiagnosticSeverity severity) {

        List<Diagnostic> result = new ArrayList<>();
        DrlxParser parser = DrlxHoverHelper.createParser(text);
        ParseTree parseTree = parser.drlxStart();

        DrlxCompilationUnitContext cu = findDrlxCompilationUnit(parseTree);
        Set<String> imports = new HashSet<>();
        String unitPackage = null;

        if (cu != null) {
            if (cu.unitDeclaration() != null && cu.unitDeclaration().qualifiedName() != null) {
                String unitFqcn = cu.unitDeclaration().qualifiedName().getText();
                if (unitFqcn.contains(".")) {
                    unitPackage = unitFqcn.substring(0, unitFqcn.lastIndexOf('.'));
                }
            }
            if (cu.importDeclaration() != null) {
                for (ImportDeclarationContext imp : cu.importDeclaration()) {
                    if (imp.qualifiedName() != null) {
                        imports.add(imp.qualifiedName().getText());
                    }
                }
            }
        }

        // Resolvable suggestion pool at the use site
        Set<String> suggestionPool = new HashSet<>();
        for (String imp : imports) {
            String simple = imp.contains(".") ? imp.substring(imp.lastIndexOf('.') + 1) : imp;
            suggestionPool.add(simple);
        }
        if (unitPackage != null) {
            for (String simple : model.classIndex().simpleNames()) {
                if (model.classIndex().containsFqcn(unitPackage + "." + simple)) {
                    suggestionPool.add(simple);
                }
            }
        }
        for (String simple : model.classIndex().simpleNames()) {
            List<String> fqcns = model.classIndex().getBySimpleName(simple);
            for (String fqcn : fqcns) {
                if (fqcn.startsWith("java.lang.")) {
                    suggestionPool.add(simple);
                    break;
                }
            }
        }

        // 1. Scan explicit type declarations in rules (RuleParameter, BoundOopath, LocalVariableDeclaration)
        collectRuleDeclaredTypes(parseTree, model, imports, unitPackage, suggestionPool, severity, result);

        // 2. Scan RHS new T(...) instantiations
        Matcher m = NEW_TYPE_PATTERN.matcher(sanitized);
        while (m.find() && result.size() < MAX_UNKNOWN_TYPE_DIAGNOSTICS) {
            String typeName = m.group(1);
            int startOffset = m.start(1);
            int endOffset = m.end(1);
            Range range = offsetToRange(sanitized, startOffset, endOffset);
            checkType(typeName, range, model, imports, unitPackage, suggestionPool, severity, result);
        }

        return result;
    }

    private static void collectRuleDeclaredTypes(
            ParseTree node, WorkspaceSemanticModel model, Set<String> imports,
            String unitPackage, Set<String> suggestionPool, DiagnosticSeverity severity, List<Diagnostic> result) {

        if (result.size() >= MAX_UNKNOWN_TYPE_DIAGNOSTICS) {
            return;
        }

        if (node instanceof BoundOopathContext bound) {
            if (bound.identifier().size() >= 2) {
                String typeName = bound.identifier(0).getText();
                if (!"var".equals(typeName)) {
                    var token = bound.identifier(0).getStart();
                    Range range = new Range(
                            new Position(token.getLine() - 1, token.getCharPositionInLine()),
                            new Position(token.getLine() - 1, token.getCharPositionInLine() + typeName.length())
                    );
                    checkType(typeName, range, model, imports, unitPackage, suggestionPool, severity, result);
                }
            }
            return;
        }

        if (node instanceof RuleParameterContext ruleParam) {
            if (ruleParam.typeType() != null) {
                String typeName = ruleParam.typeType().getText();
                var token = ruleParam.typeType().getStart();
                Range range = new Range(
                        new Position(token.getLine() - 1, token.getCharPositionInLine()),
                        new Position(token.getLine() - 1, token.getCharPositionInLine() + typeName.length())
                );
                checkType(typeName, range, model, imports, unitPackage, suggestionPool, severity, result);
            }
            return;
        }

        if (node instanceof LocalVariableDeclarationContext localVar) {
            if (localVar.typeType() != null) {
                String typeName = localVar.typeType().getText();
                var token = localVar.typeType().getStart();
                Range range = new Range(
                        new Position(token.getLine() - 1, token.getCharPositionInLine()),
                        new Position(token.getLine() - 1, token.getCharPositionInLine() + typeName.length())
                );
                checkType(typeName, range, model, imports, unitPackage, suggestionPool, severity, result);
            }
            return;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectRuleDeclaredTypes(node.getChild(i), model, imports, unitPackage, suggestionPool, severity, result);
        }
    }

    private static void checkType(
            String typeName, Range range, WorkspaceSemanticModel model,
            Set<String> imports, String unitPackage, Set<String> suggestionPool,
            DiagnosticSeverity severity, List<Diagnostic> result) {

        if (typeName == null || typeName.isEmpty()) {
            return;
        }
        String cleanType = typeName.endsWith("[]") ? typeName.substring(0, typeName.indexOf('[')) : typeName;

        if (isTypeKnown(cleanType, model, imports, unitPackage)) {
            return;
        }

        String simpleName = cleanType.contains(".") ? cleanType.substring(cleanType.lastIndexOf('.') + 1) : cleanType;
        String suggestion = bestTypoSuggestion(simpleName, suggestionPool);

        Diagnostic d = new Diagnostic();
        d.setRange(range);
        d.setSeverity(severity);
        d.setSource("drlx-type");
        if (suggestion != null) {
            d.setMessage("Unknown type '" + typeName + "'. Did you mean '" + suggestion + "'?");
            d.setData(suggestion);
        } else {
            d.setMessage("Unknown type '" + typeName + "'");
        }
        result.add(d);
    }

    private static boolean isTypeKnown(
            String typeName, WorkspaceSemanticModel model, Set<String> imports, String unitPackage) {

        if (typeName.contains(".")) {
            if (model.classIndex().containsFqcn(typeName)) {
                return true;
            }
            try {
                model.typeSolver().solveType(typeName);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }

        // Simple name
        for (String imp : imports) {
            if (imp.endsWith("." + typeName)) {
                return true;
            }
        }
        if (unitPackage != null && model.classIndex().containsFqcn(unitPackage + "." + typeName)) {
            return true;
        }
        try {
            model.typeSolver().solveType("java.lang." + typeName);
            return true;
        } catch (Exception ignored) { }
        try {
            model.typeSolver().solveType(typeName);
            return true;
        } catch (Exception ignored) { }

        return false;
    }

    private static String bestTypoSuggestion(String target, Set<String> candidatePool) {
        String bestCandidate = null;
        int bestDistance = 3; // only bounded <= 2
        for (String candidate : candidatePool) {
            int dist = levenshteinDistance(target, candidate, 2);
            if (dist <= 2 && dist < bestDistance) {
                bestDistance = dist;
                bestCandidate = candidate;
            }
        }
        return bestCandidate;
    }

    static int levenshteinDistance(String s1, String s2, int maxBound) {
        if (s1.equals(s2)) return 0;
        int len1 = s1.length();
        int len2 = s2.length();
        if (Math.abs(len1 - len2) > maxBound) return maxBound + 1;

        int[] prev = new int[len2 + 1];
        int[] curr = new int[len2 + 1];

        for (int j = 0; j <= len2; j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= len1; i++) {
            curr[0] = i;
            int minVal = curr[0];
            for (int j = 1; j <= len2; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                minVal = Math.min(minVal, curr[j]);
            }
            if (minVal > maxBound) return maxBound + 1;
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }
        return prev[len2];
    }

    private static Range offsetToRange(String text, int startOffset, int endOffset) {
        Position start = offsetToPosition(text, startOffset);
        Position end = offsetToPosition(text, endOffset);
        return new Range(start, end);
    }

    private static Position offsetToPosition(String text, int offset) {
        int line = 0;
        int col = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        return new Position(line, col);
    }

    private static DrlxCompilationUnitContext findDrlxCompilationUnit(ParseTree node) {
        if (node instanceof DrlxCompilationUnitContext cu) return cu;
        for (int i = 0; i < node.getChildCount(); i++) {
            DrlxCompilationUnitContext found = findDrlxCompilationUnit(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }
}
