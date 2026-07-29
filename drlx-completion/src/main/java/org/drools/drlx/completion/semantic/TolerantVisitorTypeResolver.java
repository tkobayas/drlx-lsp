package org.drools.drlx.completion.semantic;

import java.util.Map;
import java.util.Optional;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import org.drools.drlx.parser.TolerantDrlxToJavaParserVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TolerantVisitorTypeResolver implements ExpressionTypeResolver {

    private static final Logger logger = LoggerFactory.getLogger(TolerantVisitorTypeResolver.class);

    @Override
    public Optional<SemanticType> resolve(
            CompletionExpression expression,
            VisibleSymbols symbols,
            WorkspaceTypes workspaceTypes) {

        int scopeTokenIndex = expression.scopeTokenIndex();
        if (scopeTokenIndex < 0) {
            return Optional.empty();
        }

        TolerantDrlxToJavaParserVisitor visitor = new TolerantDrlxToJavaParserVisitor();
        CompilationUnit compilationUnit = (CompilationUnit) visitor.visit(expression.parseTree());

        JavaSymbolSolver solver = new JavaSymbolSolver(workspaceTypes.typeSolver());
        solver.inject(compilationUnit);

        Map<Integer, Node> tokenIdJPNodeMap = visitor.getTokenIdJPNodeMap();
        Expression scopeNode = (Expression) tokenIdJPNodeMap.get(scopeTokenIndex);
        if (scopeNode == null) {
            logger.info("scopeNode is null");
            return Optional.empty();
        }

        logger.info("scopeNode: {} , text => [{}]", scopeNode.getClass(), scopeNode);
        try {
            ResolvedType resolvedType = scopeNode.calculateResolvedType();
            return Optional.of(SemanticType.value(resolvedType));
        } catch (Exception e) {
            logger.info("Failed to resolve type for scope node: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
