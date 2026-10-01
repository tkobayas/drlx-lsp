package org.drools.drlx.completion;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DrlxDocCommentParser {

    private static final Pattern DOC_BLOCK =
            Pattern.compile("(?s)/\\*\\*(?!\\*)(.*?)\\*/");

    private static final Pattern DECL_AFTER_DOC = Pattern.compile(
            "(?s)\\A(?:\\s*//[^\\n]*\\n|\\s|@\\w+(?:\\([^)]*\\))?)*"
            + "(?:"
            +   "rule\\s+(\\w+)"
            +   "|unit\\s+([\\w.]+)"
            +   "|window\\s+(\\w+)"
            + ")");

    private DrlxDocCommentParser() {
    }

    public static Map<String, String> parseDocs(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> docs = new HashMap<>();
        Matcher docMatcher = DOC_BLOCK.matcher(text);
        while (docMatcher.find()) {
            String body = stripDocStars(docMatcher.group(1));
            if (body.isEmpty()) {
                continue;
            }
            Matcher declMatcher = DECL_AFTER_DOC.matcher(text.substring(docMatcher.end()));
            if (!declMatcher.find()) {
                continue;
            }
            String name = firstNonNull(declMatcher.group(1), declMatcher.group(2),
                                       declMatcher.group(3));
            if (name != null && !name.isEmpty()) {
                docs.putIfAbsent(name, body);
            }
        }
        return docs;
    }

    public static String docFor(String text, String name) {
        if (text == null || name == null || name.isEmpty()) {
            return null;
        }
        return parseDocs(text).get(name);
    }

    static String stripDocStars(String raw) {
        if (raw == null) {
            return "";
        }
        String[] lines = raw.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].replaceFirst("^\\s*\\*\\s?", "");
            int end = stripped.length();
            while (end > 0 && Character.isWhitespace(stripped.charAt(end - 1))) {
                end--;
            }
            sb.append(stripped, 0, end);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString().strip();
    }

    private static String firstNonNull(String... candidates) {
        for (String c : candidates) {
            if (c != null) {
                return c;
            }
        }
        return null;
    }
}
