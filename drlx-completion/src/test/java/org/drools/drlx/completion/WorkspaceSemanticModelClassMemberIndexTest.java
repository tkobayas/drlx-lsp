package org.drools.drlx.completion;

import org.drools.drlx.completion.semantic.ClassMemberIndex;
import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceSemanticModelClassMemberIndexTest {

    @Test
    void classMemberIndex_isAvailableAfterConstruction() {
        WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
        ClassMemberIndex memberIndex = model.classMemberIndex();
        assertThat(memberIndex).isNotNull();
    }
}
