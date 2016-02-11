/**
 * Copyright (c) Codice Foundation
 * <p/>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * <p/>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package ddf.catalog.data.dynamic;

import ddf.catalog.data.Metacard;
import org.apache.commons.beanutils.LazyDynaBean;
import org.apache.commons.beanutils.LazyDynaClass;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * MetacardFactory is used to manage registered metacard types and to generate instances of
 * requested types. This class includes the definition for the basic DynamicMetacard and ensures
 * that all generated metacards include the DynamicMetacard properties.
 */
public class MetacardFactory {
    private static Map<String, LazyDynaClass> dynaClassMap = new HashMap<>();

    private static MetacardPropertyDescriptor[] metacardPropertyDescriptors;

    static {
        metacardPropertyDescriptors = new MetacardPropertyDescriptor[]{
                new MetacardPropertyDescriptor(Metacard.CREATED, Date.class),
                new MetacardPropertyDescriptor(Metacard.MODIFIED, Date.class),
                new MetacardPropertyDescriptor(Metacard.EXPIRATION, Date.class),
                new MetacardPropertyDescriptor(Metacard.EFFECTIVE, Date.class),
                new MetacardPropertyDescriptor(Metacard.ID, String.class),
                new MetacardPropertyDescriptor(Metacard.GEOGRAPHY, String.class), // ("location", String.class),
                new MetacardPropertyDescriptor(DynamicMetacard.SOURCE_ID, String.class), // ("sourceId", String.class),
                new MetacardPropertyDescriptor(Metacard.THUMBNAIL, byte[].class),
                new MetacardPropertyDescriptor(Metacard.TITLE, String.class),
                new MetacardPropertyDescriptor(Metacard.METADATA, String.class),
                //new MetacardPropertyDescriptor(DynamicMetacard.METACARD_TYPE, MetacardType.class), // ("metacardType", MetacardType.class),
                new MetacardPropertyDescriptor(Metacard.TARGET_NAMESPACE, String.class), // ("contentTypeNamespace", URI.class),
                new MetacardPropertyDescriptor(Metacard.CONTENT_TYPE, String.class), // ("contentTypeName", String.class),
                new MetacardPropertyDescriptor(Metacard.POINT_OF_CONTACT, String.class),
                new MetacardPropertyDescriptor(Metacard.DESCRIPTION, String.class),
                new MetacardPropertyDescriptor(Metacard.CONTENT_TYPE_VERSION, String.class),
                new MetacardPropertyDescriptor(Metacard.RESOURCE_URI, String.class),
                new MetacardPropertyDescriptor(Metacard.RESOURCE_SIZE, String.class),
                new MetacardPropertyDescriptor(Metacard.SECURITY, Map.class)
        };
        dynaClassMap.put(DynamicMetacard.DYNAMIC, new LazyDynaClass(DynamicMetacard.DYNAMIC, null,
                metacardPropertyDescriptors));
    }

    /**
     * Generates an instance of the default DynamicMetacard.
     * @return an instance of the default DynamicMetacard
     */
    public static DynamicMetacard newInstance() {
        return newInstance(DynamicMetacard.DYNAMIC);
    }

    /**
     * Creates a new metacard instance corresponding the the name provided. Any registered metacard type
     * can be generated. Returns null if the requested metacard type has not been registered.
     * @param name the name of the type of metacard to create
     * @return an instance of the specified type of metacard, or null if the specified type has not been registered
     */
    public static DynamicMetacard newInstance(String name)  { //throws InstantiationException, IllegalAccessException {
        LazyDynaBean lazyDynaBean = null;
        LazyDynaClass dynaClass = dynaClassMap.get(name);
        if (dynaClass != null) {
            lazyDynaBean = new LazyDynaBean(dynaClass);
        }
        DynamicMetacard dynaMetacard = new DynamicMetacard(lazyDynaBean);
        return dynaMetacard;
    }

    /**
     * Returns an array of {@link MetacardPropertyDescriptor}s representing the core attributes used in the basic
     * {@link DynamicMetacard} - useful for combining with other property descriptors to generate a new type of metacard.
     * @return array of {@link MetacardAttributeDescriptor}s
     */
    public static MetacardPropertyDescriptor[] getBaseMetacardPropertyDescriptors() {
        return metacardPropertyDescriptors;
    }

    /**
     * Adds a new type of metacard definition. Accepts a {@link LazyDynaClass} containing all the properties for the
     * new class.
     * @param dynaClass describes the property set that will be included in this new metacard type
     */
    public static void addDynaClass(LazyDynaClass dynaClass) {
        if (dynaClass != null) {
            dynaClassMap.put(dynaClass.getName(), dynaClass);
        }
    }
}
