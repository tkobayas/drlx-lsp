package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public final class DrlxLintHelper {

    private static final int MAX_DIAGNOSTICS = 20;

    private DrlxLintHelper() {
    }

    public static List<Diagnostic> lint(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        DiagnosticSeverity severity = resolveSeverity();
        if (severity == null) {
            return Collections.emptyList();
        }
        String sanitized = sanitize(text);
        return lintUnbalancedOopathBrackets(sanitized, severity);
    }

    // ---- configuration ------------------------------------------------

    private static DiagnosticSeverity resolveSeverity() {
        String val = System.getProperty("drlx.lsp.lint.unbalancedOopathBrackets", "warning")
                .trim().toLowerCase();
        return switch (val) {
            case "off"     -> null;
            case "hint"    -> DiagnosticSeverity.Hint;
            case "info"    -> DiagnosticSeverity.Information;
            case "error"   -> DiagnosticSeverity.Error;
            default        -> DiagnosticSeverity.Warning; // "warning" and unknown values
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
}
