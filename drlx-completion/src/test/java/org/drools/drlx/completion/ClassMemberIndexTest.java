package org.drools.drlx.completion;

import java.util.List;
import java.util.Set;

import org.drools.drlx.completion.semantic.ClassMemberIndex;
import org.drools.drlx.completion.semantic.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassMemberIndexTest {

    private ClassMemberIndex index;

    @BeforeEach
    void setUp() {
        // Use the test classloader directly — domain classes are on the test classpath
        index = new ClassMemberIndex(Thread.currentThread().getContextClassLoader());
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    @Test
    void membersOf_beanProperties() {
        List<Field> members = index.membersOf("org.drools.drlx.domain.Person");
        assertThat(members).extracting(Field::name)
                .contains("name", "age", "address", "previousAddresses");
    }

    @Test
    void membersOf_propertyTypes() {
        List<Field> members = index.membersOf("org.drools.drlx.domain.Person");
        Field nameField = members.stream()
                .filter(f -> f.name().equals("name")).findFirst().orElseThrow();
        assertThat(nameField.typeFqcn()).isEqualTo("java.lang.String");

        Field ageField = members.stream()
                .filter(f -> f.name().equals("age")).findFirst().orElseThrow();
        assertThat(ageField.typeFqcn()).isEqualTo("int");
    }

    @Test
    void membersOf_genericReturnType_usesErasure() {
        List<Field> members = index.membersOf("org.drools.drlx.domain.Person");
        Field prevAddr = members.stream()
                .filter(f -> f.name().equals("previousAddresses")).findFirst().orElseThrow();
        assertThat(prevAddr.typeFqcn()).isEqualTo("java.util.List");
    }

    @Test
    void membersOf_excludesObjectMethods() {
        List<Field> members = index.membersOf("org.drools.drlx.domain.Person");
        assertThat(members).extracting(Field::name)
                .doesNotContain("class");
    }

    @Test
    void membersOf_objectClass_isEmpty() {
        List<Field> members = index.membersOf("java.lang.Object");
        assertThat(members).isEmpty();
    }

    @Test
    void membersOf_enumConstants() {
        List<Field> members = index.membersOf("java.time.DayOfWeek");
        assertThat(members).extracting(Field::name)
                .contains("MONDAY", "TUESDAY", "WEDNESDAY");
    }

    @Test
    void membersOf_unknownClass() {
        List<Field> members = index.membersOf("com.nonexistent.Foo");
        assertThat(members).isEmpty();
    }

    @Test
    void membersOf_caching() {
        List<Field> first = index.membersOf("org.drools.drlx.domain.Person");
        List<Field> second = index.membersOf("org.drools.drlx.domain.Person");
        assertThat(first).isSameAs(second);
    }

    @Test
    void memberNames_knownClass() {
        Set<String> names = index.memberNames("org.drools.drlx.domain.Person");
        assertThat(names).isNotNull();
    }

    @Test
    void memberNames_unknownClass() {
        Set<String> names = index.memberNames("com.nonexistent.Foo");
        assertThat(names).isNull();
    }

    @Test
    void empty_resolvesNothing() {
        ClassMemberIndex emptyIndex = ClassMemberIndex.empty();
        assertThat(emptyIndex.membersOf("org.drools.drlx.domain.Person")).isEmpty();
        assertThat(emptyIndex.memberNames("org.drools.drlx.domain.Person")).isNull();
    }
}
