package com.marklogic.developer.corb.util;

import com.marklogic.developer.corb.CorbException;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSOutput;
import org.w3c.dom.ls.LSSerializer;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.Source;
import javax.xml.transform.stax.StAXSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.*;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI;
/**
 * @since 2.4.0
 */
public class XmlUtils {

    private static final Logger LOG = Logger.getLogger(XmlUtils.class.getName());

    private XmlUtils() {
    }

    public static String documentToString(Document doc)    {
        return nodeToString(doc, doc);
    }

    public static String nodeToString(Node node) {
        if (node instanceof Document) {
            return documentToString((Document) node);
        } else {
            return nodeToString(node.getOwnerDocument(), node);
        }
    }

    public static String nodeToString(Document doc, Node node) {
        DOMImplementationLS domImplementation = (DOMImplementationLS) doc.getImplementation();
        LSSerializer lsSerializer = domImplementation.createLSSerializer();
        lsSerializer.getDomConfig().setParameter("xml-declaration", false);
        LSOutput lsOutput =  domImplementation.createLSOutput();
        lsOutput.setEncoding("UTF-8");

        Writer stringWriter = new StringWriter();
        lsOutput.setCharacterStream(stringWriter);
        lsSerializer.write(node, lsOutput);
        return stringWriter.toString();
    }

    public static List<SAXParseException> schemaValidate(File xmlFile, File schemaFile) throws CorbException {
        XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();
        try (Reader fileReader = new FileReader(xmlFile)) {
            Source source = new StAXSource(xmlInputFactory.createXMLStreamReader(fileReader));
            return schemaValidate(source, schemaFile);
        } catch (IOException | SAXException | XMLStreamException ex) {
            LOG.log(Level.SEVERE, "Unable to schema validate XML file", ex);
            throw new CorbException(ex.getMessage(), ex);
        }
    }

    public static List<SAXParseException> schemaValidate(Source source, File schemaFile) throws SAXException, IOException {
        SchemaFactory schemaFactory = SchemaFactory.newInstance(W3C_XML_SCHEMA_NS_URI);
        Schema schema = schemaFactory.newSchema(schemaFile);
        Validator validator = schema.newValidator();
        final List<SAXParseException> exceptions = new LinkedList<>();
        //collect all validation errors with a custom handler
        validator.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) throws SAXException {
                exceptions.add(exception);
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                exceptions.add(exception);
            }

            @Override
            public void error(SAXParseException exception) throws SAXException {
                exceptions.add(exception);
            }
        });
        validator.validate(source);
        return exceptions;
    }

    public static InputStream toInputStream(Node node) {
        return new ByteArrayInputStream(nodeToString(node.getOwnerDocument(), node).getBytes());
    }
}
