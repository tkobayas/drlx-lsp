package org.drools.drlx.completion.semantic;

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
 *
 * <p>For the baseline adapter, this is empty — the existing tolerant visitor
 * does not perform scope-aware binding lookup.
 */
public class VisibleSymbols {

    private static final VisibleSymbols EMPTY = new VisibleSymbols();

    public static VisibleSymbols empty() {
        return EMPTY;
    }

    public Optional<SemanticType> lookup(String name) {
        return Optional.empty();
    }
}
