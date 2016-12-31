/*
 * Copyright (c) 2004-2017 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * The use of the Apache License does not indicate that this project is
 * affiliated with the Apache Software Foundation.
 */
package com.marklogic.developer.corb;

/**
 *
 * @author Praveen Venkata
 */
import static com.marklogic.developer.corb.Options.XML_FILE;
import static com.marklogic.developer.corb.Options.XML_NODE;
import static com.marklogic.developer.corb.util.StringUtils.isBlank;
import static com.marklogic.developer.corb.util.StringUtils.trim;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @since 2.3.1
 */
public class FileUrisXMLLoader extends AbstractUrisLoader {

    protected static final Logger LOG = Logger.getLogger(FileUrisXMLLoader.class.getName());
    private static final String EXCEPTION_MSG_PROBLEM_READING_XML_FILE = "Problem while reading the xml file";
    protected String nextUri;
    protected Iterator<Node> nodeIterator;
    protected Document doc;

    private Map<Integer, Node> nodeMap;
    private TransformerFactory transformerFactory;
    private static final String YES = "yes";

    @Override
    public void open() throws CorbException {

        try {
            String fileName = getProperty(XML_FILE);
            String xpathRootNode = getProperty(XML_NODE);

            File fXmlFile = new File(fileName);
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            doc = dBuilder.parse(fXmlFile);

            //Get Child nodes for parent node which is a wrapper node
            NodeList nodeList;

            if (xpathRootNode == null) {
                //default processing will select child elements
                nodeList = doc.getChildNodes().item(0).getChildNodes();
            } else {
                XPathFactory factory = XPathFactory.newInstance();
                //using this factory to create an XPath object:
                XPath xpath = factory.newXPath();

                // XPath Query for showing all nodes value
                XPathExpression expr = xpath.compile(xpathRootNode);
                Object result = expr.evaluate(doc, XPathConstants.NODESET);
                nodeList = (NodeList) result;
            }

            nodeMap = new ConcurrentHashMap<>(nodeList.getLength());

            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (xpathRootNode == null && node.getNodeType() != Node.ELEMENT_NODE) {
                    continue; //default processing without an XPath selects only /*
                }
                nodeMap.put(i, node);
            }

            this.setTotalCount(nodeMap.size());
            nodeIterator = nodeMap.values().iterator();

        } catch (Exception exc) {
            throw new CorbException("Problem loading data from xml file ", exc);
        }
    }

    private String nodeToString(Node node) throws CorbException {
        StringWriter sw = new StringWriter();
        try {
            //Creating a transformerFactory is expensive, only do it once
            if (transformerFactory == null) {
                transformerFactory = TransformerFactory.newInstance();
            }
            Transformer t = transformerFactory.newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, YES);
            t.setOutputProperty(OutputKeys.INDENT, YES);
            t.transform(new DOMSource(node), new StreamResult(sw));
        } catch (TransformerException te) {
            throw new CorbException("nodeToString Transformer Exception", te);
        }
        return sw.toString();
    }

    private String readNextNode() throws CorbException {
        if (nodeIterator.hasNext()) {
            Node nextNode = nodeIterator.next();
            short nextNodeType = nextNode.getNodeType();
            String line;
            if (nextNodeType == Node.ELEMENT_NODE || nextNodeType == Node.DOCUMENT_NODE) {
                line = trim(nodeToString(nextNode));
            } else {
                line = nextNode.getNodeValue();
            }
            if (isBlank(line)) {
                line = readNextNode();
            }
            return line;
        }
        return null;
    }

    @Override
    public boolean hasNext() throws CorbException {
        if (nextUri == null) {
            try {
                nextUri = readNextNode();
            } catch (Exception exc) {
                throw new CorbException(EXCEPTION_MSG_PROBLEM_READING_XML_FILE, exc);
            }
        }
        return nextUri != null;
    }

    @Override
    public String next() throws CorbException {
        String node;
        if (nextUri != null) {
            node = nextUri;
            nextUri = null;
        } else {
            try {
                node = readNextNode();
            } catch (Exception exc) {
                throw new CorbException(EXCEPTION_MSG_PROBLEM_READING_XML_FILE, exc);
            }
        }
        return node;
    }

    @Override
    public void close() {
        if (doc != null) {
            LOG.info("closing xml file reader");
            try {
                doc = null;
                if (nodeMap != null) {
                    nodeMap.clear();
                }
            } catch (Exception exc) {
                LOG.log(Level.SEVERE, "while closing xml file reader", exc);
            }
        }
    }

}
