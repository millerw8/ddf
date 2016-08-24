/**
 * Copyright (c) Codice Foundation
 * <p>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * </p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */

package ddf.catalog.metacard.inference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

import java.util.Arrays;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ddf.catalog.data.Metacard;
import ddf.catalog.data.impl.MetacardImpl;
import ddf.catalog.operation.impl.CreateRequestImpl;
import ddf.catalog.plugin.PluginExecutionException;
import ddf.catalog.plugin.StopProcessingException;

@RunWith(MockitoJUnitRunner.class)
public class TestDroolsInference {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestDroolsInference.class);

    private static final String ORIG_TITLE = "pic.png";

    private static final String NEW_TITLE = "NewTitle.png";

    private static final String DESCRIPTION = "Description to all metacards";

    @Test
    public void testCreateAttribute() throws PluginExecutionException, StopProcessingException {
        LOGGER.debug("created metacard");
        MetacardImpl metacard = new MetacardImpl();

        metacard.setTitle(ORIG_TITLE);

        DroolsInference droolsInference = new DroolsInference();
        droolsInference.setDroolsRules("src/test/resources/testRules.drl");

        CreateRequestImpl createResp = new CreateRequestImpl(Arrays.asList(metacard));
        droolsInference.process(createResp);

        assertThat(metacard.getAttribute(Metacard.TITLE).getValue(), is(NEW_TITLE));
        assertThat(metacard.getAttribute(Metacard.DESCRIPTION).getValue(), is(DESCRIPTION));
    }
}