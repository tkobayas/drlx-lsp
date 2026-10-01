package org.drools.drlx.completion;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DrlxDocFormatter {

    private static final Pattern INLINE_CODE =
            Pattern.compile("\\{@code\\s+([^}]*)\\}");

    private static final Pattern INLINE_LITERAL =
            Pattern.compile("\\{@literal\\s+([^}]*)\\}");

    private static final Pattern INLINE_LINK = Pattern.compile(
            "\\{@link(plain)?\\s+([^}\\s]+)(?:\\s+([^}]+))?\\}");

    private DrlxDocFormatter() {
    }

    public static String format(String body, Map<String, String> linkTargets) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        String out = replaceWith(body, INLINE_CODE, m -> "`" + m.group(1) + "`");
        out = replaceWith(out, INLINE_LITERAL, m -> escapeMarkdown(m.group(1)));
        out = replaceWith(out, INLINE_LINK, m -> renderLink(m, linkTargets));
        return out;
    }

    private static String renderLink(Matcher m, Map<String, String> linkTargets) {
        boolean plain = m.group(1) != null;
        String ref = m.group(2);
        String label = m.group(3);
        if (label == null || label.isBlank()) {
            label = ref;
        }

        String typeKey = ref;
        int hash = typeKey.indexOf('#');
        if (hash >= 0) {
            typeKey = typeKey.substring(0, hash);
        }

        String href = linkTargets == null ? null : linkTargets.get(typeKey);
        if (href != null && !href.isEmpty()) {
            return "[" + label + "](" + href + ")";
        }
        return plain ? label : ("`" + label + "`");
    }

    private static String escapeMarkdown(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '`' || c == '*' || c == '_' || c == '{'
                    || c == '}' || c == '[' || c == ']' || c == '<' || c == '>'
                    || c == '#' || c == '+' || c == '-' || c == '.' || c == '!') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String replaceWith(String input, Pattern p,
                                      Function<Matcher, String> mapper) {
        Matcher m = p.matcher(input);
        StringBuilder sb = new StringBuilder(input.length());
        int last = 0;
        while (m.find()) {
            sb.append(input, last, m.start());
            sb.append(mapper.apply(m));
            last = m.end();
        }
        sb.append(input, last, input.length());
        return sb.toString();
    }
}
