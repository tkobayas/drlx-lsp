package org.drools.drlx.domain;

import org.drools.ruleunits.api.DataSource;
import org.drools.ruleunits.api.DataStore;

public class MyUnit {

    public DataStore<Person> persons = DataSource.createStore();
}
