package org.drools.drlx.completion.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionFieldDeclaration;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionMethodDeclaration;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

import static org.drools.drlx.completion.DrlxCompletionHelper.createCompletionItem;

public class MemberCompletionProvider {

    public List<CompletionItem> completions(SemanticType type) {
        if (type.resolvedType() == null) {
            return List.of();
        }

        List<CompletionItem> items = new ArrayList<>();

        try {
            ResolvedType resolvedType = type.resolvedType();
            if (resolvedType.isReferenceType()) {
                ResolvedReferenceType referenceType = resolvedType.asReferenceType();

                for (ResolvedFieldDeclaration field : referenceType.getAllFieldsVisibleToInheritors()) {
                    if (isAccessible(field)) {
                        CompletionItem item = createCompletionItem(field.getName(), CompletionItemKind.Field);
                        item.setDetail(field.getType().describe());
                        items.add(item);
                    }
                }

                referenceType.getAllMethods().stream()
                        .filter(method -> isAccessible(method))
                        .filter(method -> !method.getName().startsWith("$"))
                        .map(method -> method.getName())
                        .distinct()
                        .forEach(methodName -> items.add(createCompletionItem(methodName, CompletionItemKind.Method)));

                addDirectPropertyAccess(items);

            } else if (resolvedType.isArray()) {
                items.add(createCompletionItem("length", CompletionItemKind.Field));
            }
        } catch (Exception e) {
            System.err.println("Error resolving type members: " + e.getMessage());
        }

        return items;
    }

    private void addDirectPropertyAccess(List<CompletionItem> items) {
        Set<CompletionItem> propertyNames = items.stream()
                .filter(item -> item.getKind() == CompletionItemKind.Method)
                .map(CompletionItem::getInsertText)
                .filter(name -> name.startsWith("get") || name.startsWith("is"))
                .map(name -> {
                    if (name.startsWith("get")) {
                        return name.substring(3, 4).toLowerCase() + name.substring(4);
                    } else {
                        return name.substring(2, 3).toLowerCase() + name.substring(3);
                    }
                })
                .map(propName -> createCompletionItem(propName, CompletionItemKind.Field))
                .collect(Collectors.toSet());

        items.addAll(propertyNames);
    }

    private boolean isAccessible(ResolvedFieldDeclaration field) {
        try {
            if (field instanceof ReflectionFieldDeclaration reflectionField) {
                return reflectionField.accessSpecifier() == AccessSpecifier.PUBLIC;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean isAccessible(ResolvedMethodDeclaration method) {
        try {
            if (method instanceof ReflectionMethodDeclaration reflectionMethod) {
                return reflectionMethod.accessSpecifier() == AccessSpecifier.PUBLIC;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }
}
