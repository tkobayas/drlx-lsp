package org.drools.drlx.completion.semantic;

import java.util.List;

import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedArrayType;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberCompletionProviderTest {

    private final ReflectionTypeSolver solver = new ReflectionTypeSolver(false);
    private final MemberCompletionProvider provider = new MemberCompletionProvider();

    private ResolvedReferenceType referenceType(String fqcn) {
        return new ReferenceTypeImpl(solver.solveType(fqcn));
    }

    @Test
    void referenceTypeShowsFieldsAndMethods() {
        SemanticType type = SemanticType.value(referenceType("java.lang.System"));

        List<CompletionItem> items = provider.completions(type);
        List<String> labels = items.stream().map(CompletionItem::getInsertText).toList();

        assertThat(labels).contains("out", "in", "err");
        assertThat(labels).contains("gc", "exit");
    }

    @Test
    void referenceTypeIncludesPropertyAccess() {
        SemanticType type = SemanticType.value(referenceType("java.lang.String"));

        List<CompletionItem> items = provider.completions(type);
        List<String> labels = items.stream().map(CompletionItem::getInsertText).toList();

        assertThat(labels).contains("bytes");
    }

    @Test
    void arrayTypeShowsLength() {
        var elementType = referenceType("java.lang.String");
        var arrayType = new ResolvedArrayType(elementType);
        SemanticType type = SemanticType.value(arrayType);

        List<CompletionItem> items = provider.completions(type);
        List<String> labels = items.stream().map(CompletionItem::getInsertText).toList();

        assertThat(labels).containsExactly("length");
    }

    @Test
    void unresolvedTypeReturnsEmpty() {
        SemanticType type = SemanticType.unresolved();

        List<CompletionItem> items = provider.completions(type);

        assertThat(items).isEmpty();
    }

    @Test
    void fieldsHaveCorrectKind() {
        SemanticType type = SemanticType.value(referenceType("java.lang.System"));

        List<CompletionItem> items = provider.completions(type);
        CompletionItem outField = items.stream()
                .filter(i -> "out".equals(i.getInsertText()))
                .findFirst().orElseThrow();

        assertThat(outField.getKind()).isEqualTo(CompletionItemKind.Field);
        assertThat(outField.getDetail()).isEqualTo("java.io.PrintStream");
    }
}
