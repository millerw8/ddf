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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.kie.api.io.ResourceType;
import org.kie.api.runtime.StatelessKieSession;
import org.kie.internal.KnowledgeBase;
import org.kie.internal.KnowledgeBaseFactory;
import org.kie.internal.builder.KnowledgeBuilder;
import org.kie.internal.builder.KnowledgeBuilderError;
import org.kie.internal.builder.KnowledgeBuilderErrors;
import org.kie.internal.builder.KnowledgeBuilderFactory;
import org.kie.internal.io.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ddf.catalog.data.Metacard;
import ddf.catalog.operation.CreateRequest;
import ddf.catalog.operation.DeleteRequest;
import ddf.catalog.operation.UpdateRequest;
import ddf.catalog.plugin.PluginExecutionException;
import ddf.catalog.plugin.PreIngestPlugin;
import ddf.catalog.plugin.StopProcessingException;

public class DroolsInference
        implements PreIngestPlugin,
        org.codice.ddf.platform.services.common.Describable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DroolsInference.class);

    private static final String DESCRIBABLE_PROPERTIES_FILE = "/describable.properties";

    private static final String ORGANIZATION = "organization";

    private static final String VERSION = "version";

    private static Properties describableProperties = new Properties();

    private static StatelessKieSession kSession;

    static {
        try (InputStream properties = DroolsInference.class.getResourceAsStream(
                DESCRIBABLE_PROPERTIES_FILE)) {
            describableProperties.load(properties);
        } catch (IOException e) {
            LOGGER.info("Failed to load properties", e);
        }
    }

    /**
     * Getter for the Drools rules file (.drl).
     *
     * @returns droolsRules
     */
    public String getDroolsRules() {
        return droolsRules;
    }

    /**
     * Setter for the Drools rules file (.drl).
     *
     * @param droolsRules
     */
    public void setDroolsRules(String droolsRules) {
        KnowledgeBuilder kbuilder = KnowledgeBuilderFactory.newKnowledgeBuilder();

        File file = new File(droolsRules);
        if (file.exists()) {
            kbuilder.add(ResourceFactory.newFileResource(file), ResourceType.DRL);
            KnowledgeBuilderErrors errors = kbuilder.getErrors();

            if (errors.size() > 0) {
                for (KnowledgeBuilderError error : errors) {
                    LOGGER.debug("Problem reading rules file: {}", error);
                }
            } else {
                KnowledgeBase kbase = KnowledgeBaseFactory.newKnowledgeBase();
                kbase.addKnowledgePackages(kbuilder.getKnowledgePackages());
                kSession = kbase.newStatelessKnowledgeSession();
            }
        } else {
            kSession = null;
        }
    }

    @Override
    public String getVersion() {
        return describableProperties.getProperty(VERSION);
    }

    @Override
    public String getId() {
        return this.getClass()
                .getSimpleName();
    }

    @Override
    public String getTitle() {
        return this.getClass()
                .getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Checks metacard against the local catalog for duplicates based on configurable attributes.";
    }

    @Override
    public String getOrganization() {
        return describableProperties.getProperty(ORGANIZATION);

    }

    @Override
    public CreateRequest process(CreateRequest input)
            throws PluginExecutionException, StopProcessingException {
        input.getMetacards().forEach(mc -> { runRules(mc); });
        return input;
    }

    @Override
    public UpdateRequest process(UpdateRequest input)
            throws PluginExecutionException, StopProcessingException {
        return input;
    }

    @Override
    public DeleteRequest process(DeleteRequest input)
            throws PluginExecutionException, StopProcessingException {
        return input;
    }


    private void runRules(Metacard metacard) {
        if (kSession != null) {
            LOGGER.debug("Processing rules on {}", metacard.getId());
            kSession.execute(metacard);
        } else {
            LOGGER.debug("kSession not initialized, not running rules on {}", metacard.getId());
        }

    }

}
