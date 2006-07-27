/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.jcr2spi.state;

import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.name.QName;

/**
 * <code>ItemStateFactory</code> provides methods to create child
 * <code>NodeState</code>s and <code>PropertyState</code>s for a given
 * <code>NodeState</code>.
 */
public interface ItemStateFactory {

    /**
     * Creates the child <code>NodeState</code> with the given
     * <code>uuid</code>, which has the given <code>parent</code>.
     *
     * @param parent the parent of the <code>NodeState</code> to create.
     * @param uuid   the uuid of the <code>NodeState</code> to create.
     * @return the created <code>NodeState</code>.
     * @throws NoSuchItemStateException if there is no such <code>NodeState</code>.
     * @throws ItemStateException       if an error occurs while retrieving the
     *                                  <code>NodeState</code>.
     */
    public NodeState createNodeState(NodeState parent, String uuid)
            throws NoSuchItemStateException, ItemStateException;

    /**
     * Creates the child <code>NodeState</code> with the given
     * <code>nameElement</code>, which has the given <code>parent</code>.
     *
     * @param parent      the parent of the <code>NodeState</code> to create.
     * @param nameElement the name element of the <code>NodeState</code> to
     *                    create.
     * @return the created <code>NodeState</code>.
     * @throws NoSuchItemStateException if there is no such <code>NodeState</code>.
     * @throws ItemStateException       if an error occurs while retrieving the
     *                                  <code>NodeState</code>.
     */
    public NodeState createNodeState(NodeState parent,
                                     Path.PathElement nameElement)
            throws NoSuchItemStateException, ItemStateException;

    /**
     * Creates the <code>PropertyState</code> with the given <code>name</code>,
     * which has the given <code>parent</code>.
     *
     * @param parent       the parent of the <code>PropertyState</code> to
     *                     create.
     * @param propertyName the name of the <code>PropertyState</code> to
     *                     create.
     * @return the created <code>PropertyState</code>.
     * @throws NoSuchItemStateException if there is no such <code>PropertyState</code>.
     * @throws ItemStateException       if an error occurs while retrieving the
     *                                  <code>PropertyState</code>.
     */
    public PropertyState createPropertyState(NodeState parent,
                                             QName propertyName)
            throws NoSuchItemStateException, ItemStateException;
}
