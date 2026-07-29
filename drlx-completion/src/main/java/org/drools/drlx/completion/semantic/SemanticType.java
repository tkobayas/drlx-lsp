package org.drools.drlx.completion.semantic;

import com.github.javaparser.resolution.types.ResolvedType;

/**
 * A resolved type that preserves generic type arguments and expression category.
 *
 * <p>Unlike {@code Class<?>}, which erases generics, this type retains the
 * information needed to resolve {@code DataSource<Person>} fields and generic
 * method return types. It also distinguishes whether the resolved expression
 * represents a type (where only static members apply), a value (where instance
 * members apply), an array, a primitive, or an unresolved/ambiguous reference.
 *
 * <p>Wraps JavaParser's {@code ResolvedType} internally but isolates consumers
 * from the JavaParser API so the resolver implementation can change independently.
 */
public class SemanticType {

    public enum Category { TYPE, VALUE, ARRAY, PRIMITIVE, UNRESOLVED }

    private final ResolvedType resolvedType;
    private final Category category;

    private SemanticType(ResolvedType resolvedType, Category category) {
        this.resolvedType = resolvedType;
        this.category = category;
    }

    public ResolvedType resolvedType() {
        return resolvedType;
    }

    public Category category() {
        return category;
    }

    public boolean isReferenceType() {
        return resolvedType != null && resolvedType.isReferenceType();
    }

    public boolean isArray() {
        return resolvedType != null && resolvedType.isArray();
    }

    public static SemanticType value(ResolvedType type) {
        Category cat;
        if (type.isArray()) {
            cat = Category.ARRAY;
        } else if (type.isPrimitive()) {
            cat = Category.PRIMITIVE;
        } else {
            cat = Category.VALUE;
        }
        return new SemanticType(type, cat);
    }

    public static SemanticType typeRef(ResolvedType type) {
        return new SemanticType(type, Category.TYPE);
    }

    public static SemanticType unresolved() {
        return new SemanticType(null, Category.UNRESOLVED);
    }
}
