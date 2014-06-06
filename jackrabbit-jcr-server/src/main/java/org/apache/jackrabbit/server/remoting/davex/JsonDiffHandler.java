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
package org.apache.jackrabbit.server.remoting.davex;

import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.commons.webdav.JcrRemotingConstants;
import org.apache.jackrabbit.commons.webdav.JcrValueType;
import org.apache.jackrabbit.server.util.RequestData;
import org.apache.jackrabbit.spi.Name;
import org.apache.jackrabbit.spi.commons.conversion.DefaultNamePathResolver;
import org.apache.jackrabbit.spi.commons.conversion.IllegalNameException;
import org.apache.jackrabbit.spi.commons.conversion.NamePathResolver;
import org.apache.jackrabbit.commons.json.JsonHandler;
import org.apache.jackrabbit.commons.json.JsonParser;
import org.apache.jackrabbit.core.NodeImpl;
import org.apache.jackrabbit.core.SessionImpl;
import org.apache.jackrabbit.core.id.NodeId;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.jackrabbit.core.xml.Importer;
import org.apache.jackrabbit.core.xml.SessionImporter;
import org.apache.jackrabbit.core.xml.NodeInfo;
import org.apache.jackrabbit.core.xml.PropInfo;
import org.apache.jackrabbit.core.xml.TextValue;
import org.apache.jackrabbit.util.Text;
import org.apache.jackrabbit.value.ValueHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ImportUUIDBehavior;
import javax.jcr.Item;
import javax.jcr.ItemNotFoundException;
import javax.jcr.NamespaceException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.ValueFormatException;
import javax.jcr.nodetype.NodeType;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;
import java.util.LinkedList;

/** <code>JsonDiffHandler</code>... */
class JsonDiffHandler implements DiffHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonDiffHandler.class);

    private static final String ORDER_POSITION_AFTER = "#after";
    private static final String ORDER_POSITION_BEFORE = "#before";
    private static final String ORDER_POSITION_FIRST = "#first";
    private static final String ORDER_POSITION_LAST = "#last";

    private final Session session;
    private final ValueFactory vf;
    private final String requestItemPath;
    private final RequestData data;
    private NamePathResolver resolver;
    private Importer importer;
    
    JsonDiffHandler(Session session, String requestItemPath, RequestData data) throws RepositoryException {
        this(session, requestItemPath, data, null);
    }

    JsonDiffHandler(Session session, String requestItemPath, RequestData data, Importer importer) throws RepositoryException {
        this.session = session;
        resolver = new DefaultNamePathResolver(session);
        this.requestItemPath = requestItemPath;
        this.data = data;
        this.importer = importer;
        vf = session.getValueFactory();
    }
    //--------------------------------------------------------< DiffHandler >---
    /**
     * @see DiffHandler#addNode(String, String)
     */
    public void addNode(String targetPath, String diffValue) throws DiffException {
        if (diffValue == null || !(diffValue.startsWith("{") && diffValue.endsWith("}"))) {
            throw new DiffException("Invalid 'addNode' value '" + diffValue + "'");
        }

        try {
            String itemPath = getItemPath(targetPath);
            String parentPath = Text.getRelativeParent(itemPath, 1);
            String nodeName = Text.getName(itemPath);

            addNode(parentPath, nodeName, diffValue);

        } catch (RepositoryException e) {
            throw new DiffException(e.getMessage(), e);
        }
    }

    /**
     * @see DiffHandler#setProperty(String, String) 
     */
    public void setProperty(String targetPath, String diffValue) throws DiffException {
        try {
            String itemPath = getItemPath(targetPath);
            Item item = session.getItem(Text.getRelativeParent(itemPath, 1));
            if (!item.isNode()) {
                throw new DiffException("No such node " + itemPath, new ItemNotFoundException(itemPath));
            }

            Node parent = (Node) item;
            String propName = Text.getName(itemPath);

            if (JcrConstants.JCR_MIXINTYPES.equals(propName)) {
                setMixins(parent, extractValuesFromRequest(targetPath));
            } else if (JcrConstants.JCR_PRIMARYTYPE.equals(propName)) {
                setPrimaryType(parent, extractValuesFromRequest(targetPath));
            } else {
                if (diffValue == null || diffValue.length() == 0) {
                    // single valued property with value present in multipart.
                    Value[] vs = extractValuesFromRequest(targetPath);
                    if (vs.length == 0) {
                        if (parent.hasProperty(propName)) {
                            // avoid problems with single vs. multi valued props.
                            parent.getProperty(propName).remove();
                        } else {
                            // property does not exist -> stick to rule that missing
                            // [] indicates single valued.
                            parent.setProperty(propName, (Value) null);
                        }
                    } else if (vs.length == 1) {
                        parent.setProperty(propName, vs[0]);
                    } else {
                        throw new DiffException("Unexpected number of values in multipart. Was " + vs.length + " but expected 1.");
                    }
                } else if (diffValue.startsWith("[") && diffValue.endsWith("]")) {
                    // multivalued property
                    if (diffValue.length() == 2) {
                        // empty array OR values in multipart
                        Value[] vs = extractValuesFromRequest(targetPath);
                        parent.setProperty(propName, vs);
                    } else {
                        // json array
                        Value[] vs = extractValues(diffValue);
                        parent.setProperty(propName, vs);
                    }
                } else {
                    // single prop value included in the diff
                    Value v = extractValue(diffValue);
                    parent.setProperty(propName, v);
                }
            }
        } catch (RepositoryException e) {
            throw new DiffException(e.getMessage(), e);
        } catch (IOException e) {
            if (e instanceof DiffException) {
                throw (DiffException) e;
            } else {
                throw new DiffException(e.getMessage(), e);
            }
        }
    }

    /**
     * @see DiffHandler#remove(String, String) 
     */
    public void remove(String targetPath, String diffValue) throws DiffException {
        if (!(diffValue == null || diffValue.trim().length() == 0)) {
            throw new DiffException("'remove' may not have a diffValue.");
        }
        try {
            String itemPath = getItemPath(targetPath);
            session.getItem(itemPath).remove();
        } catch (RepositoryException e) {
            throw new DiffException(e.getMessage(), e);
        }
    }

    /**
     * @see DiffHandler#move(String, String) 
     */
    public void move(String targetPath, String diffValue) throws DiffException {
        if (diffValue == null || diffValue.length() == 0) {
            throw new DiffException("Invalid 'move' value '" + diffValue + "'");
        }
        try {
            String srcPath = getItemPath(targetPath);
            String orderPosition = getOrderPosition(diffValue);
            if (orderPosition == null) {
                // simple move
                String destPath = getItemPath(diffValue);
                session.move(srcPath, destPath);
            } else {
                String srcName = Text.getName(srcPath);
                int pos = diffValue.lastIndexOf('#');
                String destName = (pos == 0) ? null : Text.getName(diffValue.substring(0, pos));

                Item item = session.getItem(Text.getRelativeParent(srcPath, 1));
                if (!item.isNode()) {
                    throw new ItemNotFoundException(srcPath);
                }
                Node parent = (Node) item;

                if (ORDER_POSITION_FIRST.equals(orderPosition)) {
                    if (destName != null) {
                        throw new DiffException(ORDER_POSITION_FIRST + " may not have a leading destination.");
                    }
                    destName = Text.getName(parent.getNodes().nextNode().getPath());
                    parent.orderBefore(srcName, destName);
                } else if (ORDER_POSITION_LAST.equals(orderPosition)) {
                    if (destName != null) {
                        throw new DiffException(ORDER_POSITION_LAST + " may not have a leading destination.");
                    }
                    parent.orderBefore(srcName, null);
                } else if (ORDER_POSITION_AFTER.equals(orderPosition)) {
                    if (destName == null) {
                        throw new DiffException(ORDER_POSITION_AFTER + " must have a leading destination.");
                    }
                    for (NodeIterator it = parent.getNodes(); it.hasNext();) {
                        Node child = it.nextNode();
                        if (destName.equals(child.getName())) {
                            if (it.hasNext()) {
                                destName = Text.getName(it.nextNode().getName());
                            } else {
                                destName = null;
                            }
                            break;
                        }
                    }
                    // reorder... if no child node matches the original destName
                    // the reorder will fail. no extra check.
                    parent.orderBefore(srcName, destName);
                } else {
                    // standard jcr reorder (before)
                    parent.orderBefore(srcName, destName);
                }
            }

        } catch (RepositoryException e) {
            throw new DiffException(e.getMessage(), e);
        }
    }

    //--------------------------------------------------------------------------
    /**
     * 
     * @param diffPath
     * @return
     * @throws RepositoryException
     */
    String getItemPath(String diffPath) throws RepositoryException {
        StringBuffer itemPath;
        if (!diffPath.startsWith("/")) {
            // diff path is relative to the item path retrieved from the
            // request URI -> calculate item path.
            itemPath = new StringBuffer(requestItemPath);
            if (!requestItemPath.endsWith("/")) {
                itemPath.append('/');
            }
            itemPath.append(diffPath);
        } else {
            itemPath = new StringBuffer(diffPath);
        }
        return normalize(itemPath.toString());
    }

    private void addNode(String parentPath, String nodeName, String diffValue)
            throws DiffException, RepositoryException {
        Item item = session.getItem(parentPath);
        if (!item.isNode()) {
            throw new ItemNotFoundException(parentPath);
        }

        Node parent = (Node) item;
        try {
            importer.start();
            NodeHandler hndlr = new NodeHandler(parent, nodeName);
            new JsonParser(hndlr).parse(diffValue);
            importer.end();
        } catch (IOException e) {
            if (e instanceof DiffException) {
                throw (DiffException) e;
            } else {
                throw new DiffException(e.getMessage(), e);
            }
        }
    }

    private static String normalize(String path) {
        if (path.indexOf('.') == -1) {
            return path;
        }
        String[]  elems = Text.explode(path, '/', false);
        LinkedList<String> queue = new LinkedList<String>();
        String last = "..";
        for (String segm : elems) {
            if ("..".equals(segm) && !"..".equals(last)) {
                queue.removeLast();
                if (queue.isEmpty()) {
                    last = "..";
                } else {
                    last = queue.getLast();
                }
            } else if (!".".equals(segm)) {
                last = segm;
                queue.add(last);
            }
        }
        return "/" + Text.implode(queue.toArray(new String[queue.size()]), "/");
    }
    
    private static void setPrimaryType(Node n, Value[] values) throws RepositoryException, DiffException {
        if (values.length == 1) {
            String ntName = values[0].getString();
            if (!ntName.equals(n.getPrimaryNodeType().getName())) {
                n.setPrimaryType(ntName);
            } // else: same primaryType as before -> nothing to do.
        } else {
            throw new DiffException("Invalid diff: jcr:primarytype cannot have multiple values, nor can it's value be removed.");
        }
    }

    private static void setMixins(Node n, Value[] values) throws RepositoryException {
        if (values.length == 0) {
            // remove all mixins
            NodeType[] mixins = n.getMixinNodeTypes();
            for (NodeType mixin : mixins) {
                String mixinName = mixin.getName();
                n.removeMixin(mixinName);
            }
        } else {
            List<String> newMixins = new ArrayList<String>(values.length);
            for (Value value : values) {
                newMixins.add(value.getString());
            }
            NodeType[] mixins = n.getMixinNodeTypes();
            for (NodeType mixin : mixins) {
                String mixinName = mixin.getName();
                if (!newMixins.remove(mixinName)) {
                    n.removeMixin(mixinName);
                }
            }
            for (String newMixinName : newMixins) {
                n.addMixin(newMixinName);
            }
        }
    }

    private static String getOrderPosition(String diffValue) {
        String position = null;
        if (diffValue.indexOf('#') > -1) {
            if (diffValue.endsWith(ORDER_POSITION_FIRST) ||
                    diffValue.endsWith(ORDER_POSITION_LAST) ||
                    diffValue.endsWith(ORDER_POSITION_BEFORE) ||
                    diffValue.endsWith(ORDER_POSITION_AFTER)) {
                position = diffValue.substring(diffValue.lastIndexOf('#'));
            } // else: apparently # is part of the move path.
        }
        return position;
    }

    private Value[] extractValuesFromRequest(String paramName) throws RepositoryException, IOException {
        ValueFactory vf = session.getValueFactory();
        Value[] vs;
        InputStream[] ins = data.getFileParameters(paramName);
        if (ins != null) {
            vs = new Value[ins.length];
            for (int i = 0; i < ins.length; i++) {
                vs[i] = vf.createValue(ins[i]);
            }
        } else {
            String[] strs = data.getParameterValues(paramName);
            if (strs == null) {
                vs = new Value[0];
            } else {
                List<Value> valList = new ArrayList<Value>(strs.length);
                for (int i = 0; i < strs.length; i++) {
                    if (strs[i] != null) {
                        String[] types = data.getParameterTypes(paramName);
                        int type = (types == null || types.length <= i) ? PropertyType.UNDEFINED : JcrValueType.typeFromContentType(types[i]);
                        if (type == PropertyType.UNDEFINED) {
                            valList.add(vf.createValue(strs[i]));
                        } else {
                            valList.add(vf.createValue(strs[i], type));
                        }
                    }
                }
                vs = valList.toArray(new Value[valList.size()]);
            }
        }
        return vs;
    }

    private Value extractValue(String diffValue) throws RepositoryException, DiffException, IOException {
        ValueHandler hndlr = new ValueHandler();
        // surround diff value { key : } to make it parsable
        new JsonParser(hndlr).parse("{\"a\":"+diffValue+"}");

        return hndlr.getValue();
    }

    private Value[] extractValues(String diffValue) throws RepositoryException, DiffException, IOException {
        ValuesHandler hndlr = new ValuesHandler();
        // surround diff value { key : } to make it parsable
        new JsonParser(hndlr).parse("{\"a\":"+diffValue+"}");
        
        return hndlr.getValues();
    }

    //--------------------------------------------------------------------------
    /**
     * Inner class used to parse a single value
     */
    private final class ValueHandler implements JsonHandler {
        private Value v;

        public void object() throws IOException {
            // ignore
        }
        public void endObject() throws IOException {
            // ignore
        }
        public void array() throws IOException {
            // ignore
        }
        public void endArray() throws IOException {
            // ignore
        }
        public void key(String key) throws IOException {
            // ignore
        }

        public void value(String value) throws IOException {
            v = (value == null) ? null : vf.createValue(value);
        }
        public void value(boolean value) throws IOException {
            v = vf.createValue(value);
        }
        public void value(long value) throws IOException {
            v = vf.createValue(value);
        }
        public void value(double value) throws IOException {
            v = vf.createValue(value);
        }

        private Value getValue() {
            return v;
        }
    }

    /**
     * Inner class used to parse the values from a simple json array
     */
    private final class ValuesHandler implements JsonHandler {
        private List<Value> values = new ArrayList<Value>();

        public void object() throws IOException {
            // ignore
        }
        public void endObject() throws IOException {
            // ignore
        }
        public void array() throws IOException {
            // ignore
        }
        public void endArray() throws IOException {
            // ignore
        }
        public void key(String key) throws IOException {
            // ignore
        }

        public void value(String value) throws IOException {
            if (value != null) {
                values.add(vf.createValue(value));
            } else {
                log.warn("Null element for a multivalued property -> Ignore.");
            }
        }
        public void value(boolean value) throws IOException {
            values.add(vf.createValue(value));
        }
        public void value(long value) throws IOException {
            values.add(vf.createValue(value));
        }
        public void value(double value) throws IOException {
            values.add(vf.createValue(value));
        }

        private Value[] getValues() {
            return values.toArray(new Value[values.size()]);
        }
    }

    /**
     * Inner class for parsing a simple json object defining a node and its
     * child nodes and/or child properties
     */
    private final class NodeHandler implements JsonHandler {
        private Node parent;
        private String key;
        private Stack<ImportItem> st = new Stack<ImportItem>();

        private NodeHandler(Node parent, String nodeName) throws IOException {
            this.parent = parent;
            key = nodeName;
        }

        public void object() throws IOException {
            boolean empty = st.empty();
            if(key.equalsIgnoreCase("property")) {
                if(!empty) {
                    ImportItem item = st.peek();
                    if( item instanceof ImportProperty) {
                        throw new DiffException("Invalid DIFF format: Cannot nest property Objects.");
                    }
                }
            } else {
                ImportNode n = new ImportNode(key);
                if (!empty) {
                    ImportItem obj = st.peek();
                    if (obj instanceof ImportNode) {
                        ((ImportNode) obj).addNode(n);
                    } else {
                        throw new DiffException("Invalid DIFF format: The JSONArray may only contain simple values.");
                    }
                }
                st.push(n);
            }
        }

        public void endObject() throws IOException {
            // element on stack must either be ImportNode or ImportProperty.
            ImportItem obj = st.pop();            
            if (!((obj instanceof ImportNode || obj instanceof ImportProperty))) {
                throw new DiffException("Invalid DIFF format.");
            }
            if (st.isEmpty()) {                
                // Property objects must be child object of node objects.
                if(!(obj instanceof ImportNode)) {
                    throw new DiffException("Invalid DIFF format.");
                }
             
                // everything parsed -> start importing all nodes and properties
                try {
                    ((ImportNode)obj).processNode();
                } catch (Exception e) {
                    log.error(e.getMessage());
                    throw new DiffException(e.getMessage(), e);
                }
            }
        }

        public void array() throws IOException {
            ImportMvProp prop = new ImportMvProp(key);
            ImportItem obj = st.peek();
            if (obj instanceof ImportNode) {
                ((ImportNode)obj).addProp(prop);
            } else {
                throw new DiffException("Invalid DIFF format: The JSONArray may only contain simple values.");
            }
            st.push(prop);
        }

        public void endArray() throws IOException {
            // element on stack must be ImportMvProp since array may only
            // contain simple values, no arrays/objects are allowed.
            ImportItem obj = st.peek();
            if (!((obj instanceof ImportMvProp))) {
                throw new DiffException("Invalid DIFF format: The JSONArray may only contain simple values.");
            }
        }

        public void key(String key) throws IOException {
            this.key = key;
        }

        public void value(String value) throws IOException {
            Value v = (value == null) ? null : vf.createValue(value);
            value(v);
        }

        public void value(boolean value) throws IOException {
            value(vf.createValue(value));;
        }

        public void value(long value) throws IOException {
            value(vf.createValue(value));
        }

        public void value(double value) throws IOException {
            value(vf.createValue(value));
        }
                
        private void value(Value v) throws IOException {
            String value = null;
            try {
                value = v.getString();
            } catch (RepositoryException e) {
                // should never get here. Value.getString() should always succeed.
                log.error(e.getMessage());
            }

            ImportItem obj = st.peek();
            // Type of the property
            if (key.equalsIgnoreCase("type")) {
                if(obj instanceof ImportProperty) {
                    ((ImportProperty)obj).setType(value);
                } else {
                    throw new DiffException("Invalid DIFF format: Only property objects have types.");
                }
            } else {
                if (obj instanceof ImportMvProp) {
                    ((ImportMvProp) obj).values.add(value);   
                } else { // single-valued property. FIXME: not allowed if ImportProp is already on stack.                                          
                    ImportProp prop = new ImportProp(key, value);                                                            
                    ((ImportNode)obj).addProp(prop);                                      
                    st.push(prop);                 
                } 
            }
        }
    }

    // >---------------------------- Utility methods for Importing Items
    // --------->
    
    public Name stringToName(String strValue) {
        Name name = null;
        if(strValue != null) {
            try {
                name = resolver.getQName(strValue);
            } catch (IllegalNameException e) {
                log.error(e.getMessage());
            } catch (NamespaceException e) {
                log.error(e.getMessage());
            } 
        }
        return name;
    }
    
    public Name[] stringToName(List<String> lst) {
        List<Name> names = new ArrayList<Name>();
        for(String str : lst) {
            names.add(stringToName(str));
        }
        return names.toArray(new Name[names.size()]);
    }
    
    private static abstract class ImportItem {
        final String name;
        protected static List<PropInfo> props;
        
        private ImportItem(String name) throws IOException {
            if (name == null) {
                throw new DiffException("Invalid DIFF format: NULL key.");
            }
            this.name = name;
            props = new ArrayList<PropInfo>();
        }

        abstract void createItemInfo() throws Exception;
    }
    
    private final class ImportNode extends ImportItem {
        private String ntName;
        private String uuid;
        private ImportMvProp mixin;
        
        // Item information.
        private NodeInfo nodeInfo;
        
        private List<ImportNode> childN = new ArrayList<ImportNode>();
        private List<ImportProperty> childP = new ArrayList<ImportProperty>();

        private ImportNode(String name) throws IOException {
            super(name);
        }
        
        void addProp(ImportProp prop) {
            if (prop.name.equals(JcrConstants.JCR_PRIMARYTYPE)) {                    
                ntName = (prop.value == null) ? null : prop.value;
                
            } else if (prop.name.equals(JcrConstants.JCR_UUID)) {              
                uuid = (prop.value == null) ? null : prop.value;
            } else {
                // regular property
                childP.add(prop);
            }
        }

        void addProp(ImportMvProp prop) {
            if(prop.name.equals(JcrConstants.JCR_MIXINTYPES)) {
                mixin = prop;
            } else {
                // regular multi-valued property
                childP.add(prop);
            }
        }       

        void addNode(ImportNode node) {
            childN.add(node);
        }

        List<String> getMixins() {
            List<String> mixins;
            if(mixin != null) {
                mixins = new ArrayList<String>();
                for(String mixinName : mixin.values) {
                    mixins.add(mixinName);
                }
                return mixins;
            }
            return null;
        }
        /**
         * Information about the node to be imported
         */
        @Override
        void createItemInfo() throws Exception {
            Name nodeName = stringToName(name);
            Name nodeType = stringToName(ntName);

            Name[] ms = null;
            List<String> mixins = getMixins();
            if(mixins != null) {
               ms = stringToName(mixins);
            }
            
            NodeId id = null;
            if(uuid != null) {
                id = NodeId.valueOf(uuid);
            }
            nodeInfo = new NodeInfo(nodeName, nodeType, ms, id);
            
            // 1. instantiate a nodeInfo object.
            // 2. processNode should pick up the nodeInfo instance
            // 3. call processNode in the addNode(string, string, string) method;
        }
        
        NodeInfo getNodeInfo() throws Exception{
            createItemInfo();
            return nodeInfo;
        }

        List<PropInfo> getPropInfos() throws Exception{
            for(ImportProperty prop : childP) {
                prop.createItemInfo();
            }
            return props;
        }

        void processNode() throws Exception {
            try {
                importer.startNode(getNodeInfo(), getPropInfos());
                props.clear();
                
                // process all child nodes.
                for(ImportNode node : childN) {
                    node.processNode();
                }                
                importer.endNode(getNodeInfo());
            }catch(RepositoryException e) {
                throw new DiffException(e.getMessage());
            }
        }
    }

    abstract class ImportProperty extends ImportItem {
        String type;
        protected PropInfo propInfo;
        
        ImportProperty(String name) throws IOException {
            super(name);
        }
        
        void setType(String type) {
            this.type = type;
        }
        
        /**
         * Template for creating a property information object.
         */
        void createItemInfo() throws Exception {
            try {
                TextValue[] txtValue = getTextValues();
                Name propName = stringToName(name);
                propInfo = new PropInfo(propName, PropertyType.valueFromName(type), txtValue);
                props.add(propInfo);
            } catch(Exception e) {
                if(e instanceof IllegalArgumentException) {
                    throw new DiffException("Invalid Diff: UNKNOWN property type ("+type+")");  
                }else {
                    throw e;
                }                
            }
        }
        abstract TextValue[] getTextValues();
    }
    
    private final class ImportProp extends ImportProperty  {
        private final String value;
        
        private ImportProp(String name, String v) throws IOException {
            super(name);
            this.value = v;
        }
        
        @Override
        TextValue[] getTextValues() {
            TextValue[] txtValue = {new StringValue(value)};
            return txtValue;
        }
    }

    private final class ImportMvProp extends ImportProperty  {
        private List<String> values = new ArrayList<String>();

        private ImportMvProp(String name) throws IOException {
            super(name);
        }

        void addValue(String v) {
            values.add(v);
        }

        @Override
        TextValue[] getTextValues() {
            List<TextValue> values = new ArrayList<TextValue>();
            for(String v : this.values) {
                values.add(new StringValue(v));
            }
            return values.toArray(new TextValue[values.size()]);
        }
    }
    
    // <------------------------- StringValue ---------------------------------------<<<<
    // FIXME: The StringValue concrete class in core/xml already provides implementation for these methods but cannot simply be
    //        accessed outside of its package. Is it better to make it accessible here for json import as well?
    private final class StringValue implements TextValue {
        String value;
        
        StringValue(String v) {
            value = v;
        }
        
        @Override
        public Value getValue(int type, NamePathResolver resolver) throws ValueFormatException, RepositoryException { 
            return (value == null ) ? null : ValueHelper.deserialize(value, type, false, vf);
        }

        @Override
        public InternalValue getInternalValue(int type)
                throws ValueFormatException, RepositoryException {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public void dispose() {
            // TODO Auto-generated method stub
        }
        
    }
    
}
