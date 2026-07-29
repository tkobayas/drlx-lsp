package org.drools.drlx.completion.semantic;

import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedArrayType;
import com.github.javaparser.resolution.types.ResolvedPrimitiveType;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticTypeTest {

    private final ReflectionTypeSolver solver = new ReflectionTypeSolver(false);

    private ResolvedReferenceType referenceType(String fqcn) {
        return new ReferenceTypeImpl(solver.solveType(fqcn));
    }

    @Test
    void valueFromReferenceType() {
        var resolved = referenceType("java.lang.String");
        SemanticType type = SemanticType.value(resolved);

        assertThat(type.category()).isEqualTo(SemanticType.Category.VALUE);
        assertThat(type.resolvedType()).isSameAs(resolved);
        assertThat(type.isReferenceType()).isTrue();
        assertThat(type.isArray()).isFalse();
    }

    @Test
    void valueFromArrayType() {
        var elementType = referenceType("java.lang.String");
        var arrayType = new ResolvedArrayType(elementType);
        SemanticType type = SemanticType.value(arrayType);

        assertThat(type.category()).isEqualTo(SemanticType.Category.ARRAY);
        assertThat(type.isArray()).isTrue();
        assertThat(type.isReferenceType()).isFalse();
    }

    @Test
    void valueFromPrimitiveType() {
        SemanticType type = SemanticType.value(ResolvedPrimitiveType.INT);

        assertThat(type.category()).isEqualTo(SemanticType.Category.PRIMITIVE);
        assertThat(type.isReferenceType()).isFalse();
        assertThat(type.isArray()).isFalse();
    }

    @Test
    void typeRef() {
        var resolved = referenceType("java.lang.System");
        SemanticType type = SemanticType.typeRef(resolved);

        assertThat(type.category()).isEqualTo(SemanticType.Category.TYPE);
        assertThat(type.resolvedType()).isSameAs(resolved);
    }

    @Test
    void unresolved() {
        SemanticType type = SemanticType.unresolved();

        assertThat(type.category()).isEqualTo(SemanticType.Category.UNRESOLVED);
        assertThat(type.resolvedType()).isNull();
        assertThat(type.isReferenceType()).isFalse();
        assertThat(type.isArray()).isFalse();
    }
}
