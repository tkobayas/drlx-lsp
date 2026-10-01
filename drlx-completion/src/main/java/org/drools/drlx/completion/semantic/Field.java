package org.drools.drlx.completion.semantic;

import java.util.List;

public record Field(String name, String typeFqcn, List<String> args) {

    public Field(String name, String typeFqcn) {
        this(name, typeFqcn, List.of());
    }
}
