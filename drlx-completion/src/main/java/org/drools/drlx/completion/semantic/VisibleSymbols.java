package org.drools.drlx.completion.semantic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The set of named symbols visible at a specific caret position within a rule.
 *
 * <p>Scope rules enforce: current compilation unit only, current rule only,
 * declarations appearing before the caret, nearest declaration when names are
 * shadowed, and nested block scoping. This prevents cross-rule binding leakage
 * and ensures that later declarations do not appear in earlier completions.
 *
 * <p>Symbol sources include: rule parameters, OOPath pattern bindings (with
 * types inferred from entry-point generic arguments or inline casts),
 * constraint bindings, accumulate results, and consequence local variables.
 * Each symbol maps to a {@link SemanticType} representing its resolved type.
 */
public class VisibleSymbols {

    private static final VisibleSymbols EMPTY = new VisibleSymbols(Collections.emptyMap());

    private final Map<String, SymbolEntry> symbols;

    private VisibleSymbols(Map<String, SymbolEntry> symbols) {
        this.symbols = symbols;
    }

    public static VisibleSymbols empty() {
        return EMPTY;
    }

    public Optional<SemanticType> lookup(String name) {
        SymbolEntry entry = symbols.get(name);
        return entry != null ? Optional.of(entry.type()) : Optional.empty();
    }

    public Optional<SymbolEntry> lookupEntry(String name) {
        return Optional.ofNullable(symbols.get(name));
    }

    public Iterable<Map.Entry<String, SemanticType>> entries() {
        Map<String, SemanticType> typeMap = new LinkedHashMap<>();
        symbols.forEach((k, v) -> typeMap.put(k, v.type()));
        return typeMap.entrySet();
    }

    public boolean isEmpty() {
        return symbols.isEmpty();
    }

    public static class Builder {
        private final Map<String, SymbolEntry> map = new LinkedHashMap<>();

        public Builder add(String name, SemanticType type) {
            map.put(name, new SymbolEntry(type, null));
            return this;
        }

        public Builder add(String name, SemanticType type, TokenRange range) {
            map.put(name, new SymbolEntry(type, range));
            return this;
        }

        public VisibleSymbols build() {
            if (map.isEmpty()) {
                return EMPTY;
            }
            return new VisibleSymbols(new LinkedHashMap<>(map));
        }
    }
}
