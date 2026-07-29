package org.drools.drlx.completion.semantic;

import java.util.Optional;

public interface ExpressionTypeResolver {

    Optional<SemanticType> resolve(
            CompletionExpression expression,
            VisibleSymbols symbols,
            WorkspaceTypes workspaceTypes);
}
