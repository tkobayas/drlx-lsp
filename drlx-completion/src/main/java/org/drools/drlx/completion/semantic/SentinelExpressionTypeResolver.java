package org.drools.drlx.completion.semantic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import org.mvel3.parser.ast.expr.NullSafeFieldAccessExpr;
import com.github.javaparser.resolution.types.ResolvedType;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.mvel3.ClassManager;
import org.mvel3.MVEL;
import org.mvel3.MVELCompiler;
import org.mvel3.Type;
import org.mvel3.transpiler.TranspiledResult;
import org.mvel3.transpiler.context.Declaration;
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
            if (tokens.get(i).getType() == DrlxLexer.EXCL_DOT) {
                sb.append(".");
            } else {
                sb.append(tokens.get(i).getText());
            }
        }
        sb.append(SENTINEL);
        String repairedText = sb.toString();
        logger.info("Repaired expression: [{}]", repairedText);

        try {
            Set<String> imports = extractImports(expression.parseTree());
            Declaration<?>[] declarations = toDeclarations(symbols);
            var builder = MVEL.map(declarations).<Object>out(Type.OBJECT)
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

    @SuppressWarnings("unchecked")
    private Declaration<?>[] toDeclarations(VisibleSymbols symbols) {
        if (symbols.isEmpty()) {
            return new Declaration<?>[0];
        }
        var list = new ArrayList<Declaration<?>>();
        for (var entry : symbols.entries()) {
            try {
                Class<?> clazz = resolvedTypeToClass(entry.getValue().resolvedType());
                if (clazz != null) {
                    list.add(Declaration.of(entry.getKey(), clazz));
                }
            } catch (Exception e) {
                logger.info("Cannot load class for declaration '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        return list.toArray(new Declaration<?>[0]);
    }

    private Class<?> resolvedTypeToClass(ResolvedType type) throws ClassNotFoundException {
        if (type.isArray()) {
            ResolvedType componentType = type.asArrayType().getComponentType();
            Class<?> componentClass = resolvedTypeToClass(componentType);
            return componentClass != null ? componentClass.arrayType() : null;
        }
        String fqcn = type.describe();
        int genericIdx = fqcn.indexOf('<');
        if (genericIdx > 0) {
            fqcn = fqcn.substring(0, genericIdx);
        }
        return Class.forName(fqcn, false, ClassLoader.getSystemClassLoader());
    }

    private Expression findSentinelScope(CompilationUnit unit) {
        for (Node node : unit.findAll(Node.class)) {
            if (node instanceof FieldAccessExpr fae && SENTINEL.equals(fae.getNameAsString())) {
                return fae.getScope();
            }
            if (node instanceof NullSafeFieldAccessExpr nsfe && SENTINEL.equals(nsfe.getNameAsString())) {
                return nsfe.getScope();
            }
        }
        return null;
    }

    private static final Set<String> DEFAULT_IMPORTS = Set.of(
            "java.math.BigDecimal",
            "java.math.BigInteger"
    );

    private Set<String> extractImports(ParseTree tree) {
        Set<String> imports = new LinkedHashSet<>(DEFAULT_IMPORTS);
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
