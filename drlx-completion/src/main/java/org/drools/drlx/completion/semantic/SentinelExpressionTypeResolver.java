package org.drools.drlx.completion.semantic;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.resolution.types.ResolvedType;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxParser;
import org.mvel3.ClassManager;
import org.mvel3.MVEL;
import org.mvel3.MVELCompiler;
import org.mvel3.Type;
import org.mvel3.transpiler.TranspiledResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SentinelExpressionTypeResolver implements ExpressionTypeResolver {

    private static final Logger logger = LoggerFactory.getLogger(SentinelExpressionTypeResolver.class);
    private static final String SENTINEL = "__sentinel__";

    @Override
    public Optional<SemanticType> resolve(
            CompletionExpression expression,
            VisibleSymbols symbols,
            WorkspaceTypes workspaceTypes) {

        CommonTokenStream tokens = (CommonTokenStream) expression.parser().getTokenStream();
        int caretTokenIndex = expression.caretTokenIndex();

        int dotTokenIndex = caretTokenIndex - 1;
        if (dotTokenIndex < 0) {
            return Optional.empty();
        }

        int boundaryIndex = TokenWalker.findExpressionBoundary(tokens, dotTokenIndex);

        StringBuilder sb = new StringBuilder();
        for (int i = boundaryIndex; i <= dotTokenIndex; i++) {
            sb.append(tokens.get(i).getText());
        }
        sb.append(SENTINEL);
        String repairedText = sb.toString();
        logger.info("Repaired expression: [{}]", repairedText);

        try {
            Set<String> imports = extractImports(expression.parseTree());
            var builder = MVEL.map().<Object>out(Type.OBJECT)
                    .expression(repairedText)
                    .classManager(new ClassManager())
                    .classLoader(ClassLoader.getSystemClassLoader());
            if (!imports.isEmpty()) {
                builder.imports(imports);
            }
            var params = builder.build();

            TranspiledResult result = new MVELCompiler().transpile(params);
            CompilationUnit unit = result.getUnit();

            Expression scopeExpr = findSentinelScope(unit);
            if (scopeExpr == null) {
                logger.info("Sentinel scope not found in transpiled AST");
                return Optional.empty();
            }

            ResolvedType resolvedType = scopeExpr.calculateResolvedType();
            return Optional.of(SemanticType.value(resolvedType));

        } catch (Exception e) {
            logger.info("Failed to resolve via sentinel: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Expression findSentinelScope(CompilationUnit unit) {
        for (Node node : unit.findAll(Node.class)) {
            if (node instanceof FieldAccessExpr fae && SENTINEL.equals(fae.getNameAsString())) {
                return fae.getScope();
            }
        }
        return null;
    }

    private Set<String> extractImports(ParseTree tree) {
        Set<String> imports = new LinkedHashSet<>();
        collectImports(tree, imports);
        return imports;
    }

    private void collectImports(ParseTree node, Set<String> imports) {
        if (node instanceof DrlxParser.ImportDeclarationContext imp) {
            if (imp.qualifiedName() != null) {
                imports.add(imp.qualifiedName().getText());
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectImports(node.getChild(i), imports);
        }
    }
}
