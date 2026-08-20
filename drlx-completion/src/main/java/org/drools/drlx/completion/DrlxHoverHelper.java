package org.drools.drlx.completion;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.completion.semantic.CompletionContext;
import org.drools.drlx.completion.semantic.SemanticType;
import org.drools.drlx.completion.semantic.SentinelExpressionTypeResolver;
import org.drools.drlx.completion.semantic.TokenWalker;
import org.drools.drlx.completion.semantic.VisibleSymbols;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DrlxHoverHelper {

    private static final Logger logger = LoggerFactory.getLogger(DrlxHoverHelper.class);

    private DrlxHoverHelper() {
    }

    public static Hover hover(String text, Position position, WorkspaceSemanticModel model) {
        if (text == null || text.isEmpty() || position == null) {
            return null;
        }

        DrlxParser parser = createParser(text);
        ParseTree parseTree = parser.drlxStart();
        CommonTokenStream tokens = (CommonTokenStream) parser.getTokenStream();

        Token hoveredToken = findTokenAt(tokens, position);
        if (hoveredToken == null || hoveredToken.getType() != DrlxLexer.IDENTIFIER) {
            return null;
        }
        String word = hoveredToken.getText();
        int tokenIndex = hoveredToken.getTokenIndex();

        CompletionContext ctx = model.createContext(parser, parseTree, tokenIndex);

        Token preceding = findPrecedingDefaultToken(tokens, tokenIndex);
        if (preceding != null && isDotToken(preceding.getType())) {
            Hover dotHover = resolveDotHover(word, preceding, tokens, ctx, model);
            if (dotHover != null) return dotHover;
        }

        VisibleSymbols symbols = ctx.buildVisibleSymbols();
        Optional<SemanticType> symbolType = symbols.lookup(word);
        if (symbolType.isPresent()) {
            return renderSymbol(word, symbolType.get());
        }

        return resolveImportHover(word, ctx, model);
    }

    private static Hover resolveDotHover(String memberName, Token dotToken,
                                          CommonTokenStream tokens, CompletionContext ctx,
                                          WorkspaceSemanticModel model) {
        int boundaryIndex = TokenWalker.findExpressionBoundary(tokens, dotToken.getTokenIndex());

        StringBuilder prefixBuilder = new StringBuilder();
        for (int i = boundaryIndex; i <= dotToken.getTokenIndex(); i++) {
            Token t = tokens.get(i);
            if (t.getType() == DrlxLexer.EXCL_DOT) {
                prefixBuilder.append(".");
            } else {
                prefixBuilder.append(t.getText());
            }
        }
        String prefixText = prefixBuilder.toString();
        if (prefixText.endsWith(".") || prefixText.endsWith("!.")) {
            prefixText = prefixText.substring(0, prefixText.lastIndexOf('.'));
        }

        VisibleSymbols symbols = ctx.buildVisibleSymbols();
        SentinelExpressionTypeResolver resolver = new SentinelExpressionTypeResolver();
        Optional<SemanticType> prefixType = resolver.resolveExpressionType(
                prefixText, symbols, ctx.imports(), model.projectClassLoader());

        if (prefixType.isEmpty() || !prefixType.get().isReferenceType()) {
            return null;
        }

        SemanticType ownerType = prefixType.get();
        String ownerName = simpleTypeName(ownerType);
        SemanticType memberType = resolveMemberType(ownerType, memberName);

        if (memberType == null) {
            return null;
        }

        return renderMember(memberName, memberType, ownerName);
    }

    private static SemanticType resolveMemberType(SemanticType ownerType, String memberName) {
        if (!ownerType.isReferenceType()) return null;
        try {
            ResolvedReferenceType refType = ownerType.resolvedType().asReferenceType();
            var typeDecl = refType.getTypeDeclaration().orElse(null);
            if (typeDecl == null) return null;

            String getterName = "get" + Character.toUpperCase(memberName.charAt(0)) + memberName.substring(1);
            String isName = "is" + Character.toUpperCase(memberName.charAt(0)) + memberName.substring(1);
            for (var method : typeDecl.getDeclaredMethods()) {
                if ((method.getName().equals(getterName) || method.getName().equals(isName))
                        && method.getNumberOfParams() == 0) {
                    return SemanticType.value(method.getReturnType());
                }
            }
            for (var field : typeDecl.getAllFields()) {
                if (field.getName().equals(memberName)) {
                    return SemanticType.value(field.getType());
                }
            }
        } catch (Exception e) {
            logger.debug("Cannot resolve member '{}': {}", memberName, e.getMessage());
        }
        return null;
    }

    private static Hover resolveImportHover(String word, CompletionContext ctx,
                                             WorkspaceSemanticModel model) {
        for (String fqcn : ctx.imports()) {
            String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            if (simpleName.equals(word)) {
                try {
                    var decl = model.typeSolver().solveType(fqcn);
                    var resolvedType = new ReferenceTypeImpl(decl);
                    SemanticType type = SemanticType.value(resolvedType);
                    return renderImportType(simpleName, fqcn, type);
                } catch (Exception e) {
                    logger.debug("Cannot resolve import type '{}': {}", fqcn, e.getMessage());
                }
            }
        }
        return null;
    }

    private static Hover renderSymbol(String name, SemanticType type) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(name).append("** : `").append(simpleTypeName(type)).append("`");
        appendMemberList(sb, type);
        return markdown(sb.toString());
    }

    private static Hover renderMember(String name, SemanticType type, String ownerName) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(name).append("** : `").append(simpleTypeName(type)).append("`");
        sb.append("\n\nField of `").append(ownerName).append("`");
        return markdown(sb.toString());
    }

    private static Hover renderImportType(String simpleName, String fqcn, SemanticType type) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(simpleName).append("** — `").append(fqcn).append("`");
        appendMemberList(sb, type);
        return markdown(sb.toString());
    }

    private static void appendMemberList(StringBuilder sb, SemanticType type) {
        if (type.resolvedType() == null || !type.resolvedType().isReferenceType()) return;
        try {
            ResolvedReferenceType refType = type.resolvedType().asReferenceType();
            var typeDecl = refType.getTypeDeclaration().orElse(null);
            if (typeDecl == null) return;

            Set<String> seen = new LinkedHashSet<>();
            for (var method : typeDecl.getDeclaredMethods()) {
                if (method.getNumberOfParams() != 0) continue;
                String methodName = method.getName();
                String propName = null;
                if (methodName.startsWith("get") && methodName.length() > 3) {
                    propName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
                } else if (methodName.startsWith("is") && methodName.length() > 2) {
                    propName = Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
                }
                if (propName != null && seen.add(propName)) {
                    try {
                        String propType = method.getReturnType().describe();
                        sb.append("\n- ").append(propName).append(" : ").append(simplifyTypeName(propType));
                    } catch (Exception ignored) { }
                }
            }
            for (ResolvedFieldDeclaration field : typeDecl.getAllFields()) {
                if (seen.add(field.getName())) {
                    try {
                        sb.append("\n- ").append(field.getName()).append(" : ").append(simplifyTypeName(field.getType().describe()));
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            logger.debug("Cannot enumerate members: {}", e.getMessage());
        }
    }

    private static String simpleTypeName(SemanticType type) {
        if (type.resolvedType() == null) return "?";
        String desc = type.resolvedType().describe();
        int dot = desc.lastIndexOf('.');
        return dot >= 0 ? desc.substring(dot + 1) : desc;
    }

    private static String simplifyTypeName(String fqcn) {
        if (fqcn.startsWith("java.lang.")) return fqcn.substring("java.lang.".length());
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    private static Token findTokenAt(CommonTokenStream tokens, Position position) {
        int line = position.getLine() + 1;
        int col = position.getCharacter();
        tokens.fill();
        for (Token token : tokens.getTokens()) {
            if (token.getType() == Token.EOF) continue;
            if (token.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (token.getLine() == line
                    && col >= token.getCharPositionInLine()
                    && col < token.getCharPositionInLine() + token.getText().length()) {
                return token;
            }
        }
        return null;
    }

    private static Token findPrecedingDefaultToken(CommonTokenStream tokens, int tokenIndex) {
        for (int i = tokenIndex - 1; i >= 0; i--) {
            Token t = tokens.get(i);
            if (t.getChannel() == Token.DEFAULT_CHANNEL) {
                return t;
            }
        }
        return null;
    }

    private static boolean isDotToken(int type) {
        return type == DrlxLexer.DOT || type == DrlxLexer.EXCL_DOT;
    }

    private static DrlxParser createParser(String text) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        return new DrlxParser(tokens);
    }

    private static Hover markdown(String content) {
        return new Hover(new MarkupContent(MarkupKind.MARKDOWN, content));
    }
}
