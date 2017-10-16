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

import static com.marklogic.developer.corb.Manager.DEFAULT_BATCH_URI_DELIM;
import static com.marklogic.developer.corb.Manager.URIS_BATCH_REF;
import static com.marklogic.developer.corb.Options.BATCH_URI_DELIM;
import static com.marklogic.developer.corb.Options.ERROR_FILE_NAME;
import static com.marklogic.developer.corb.Options.QUERY_RETRY_LIMIT;
import static com.marklogic.developer.corb.Options.QUERY_RETRY_INTERVAL;
import static com.marklogic.developer.corb.Options.QUERY_RETRY_ERROR_CODES;
import static com.marklogic.developer.corb.Options.QUERY_RETRY_ERROR_MESSAGE;
import static com.marklogic.developer.corb.TransformOptions.FAILED_URI_TOKEN;
import com.marklogic.developer.corb.util.StringUtils;
import static com.marklogic.developer.corb.util.StringUtils.commaSeparatedValuesToList;
import static com.marklogic.developer.corb.util.StringUtils.isEmpty;
import static com.marklogic.developer.corb.util.StringUtils.isNotEmpty;
import static com.marklogic.developer.corb.util.StringUtils.trim;
import com.marklogic.xcc.Request;
import com.marklogic.xcc.RequestOptions;
import com.marklogic.xcc.ResultSequence;
import com.marklogic.xcc.Session;
import com.marklogic.xcc.ValueFactory;
import com.marklogic.xcc.exceptions.QueryException;
import com.marklogic.xcc.exceptions.RequestException;
import com.marklogic.xcc.exceptions.RequestPermissionException;
import com.marklogic.xcc.exceptions.RetryableQueryException;
import com.marklogic.xcc.exceptions.ServerConnectionException;
import com.marklogic.xcc.types.XName;
import com.marklogic.xcc.types.XdmBinary;
import com.marklogic.xcc.types.XdmItem;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 *
 * @author Bhagat Bandlamudi, MarkLogic Corporation
 *
 */
public abstract class AbstractTask implements Task {

    private static final Object ERROR_SYNC_OBJ = new Object();

    protected static final String TRUE = "true";
    protected static final String FALSE = "false";
    protected static final String REQUEST_VARIABLE_DOC = "DOC";
    protected static final String REQUEST_VARIABLE_URI = "URI";
    protected static final byte[] NEWLINE
            = System.getProperty("line.separator") != null ? System.getProperty("line.separator").getBytes() : "\n".getBytes();
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    protected ContentSourcePool csp;
    protected String moduleType;
    protected String moduleUri;
    protected Properties properties = new Properties();
    protected String[] inputUris;

    protected String adhocQuery;
    protected String language;
    protected TimeZone timeZone;
    protected String exportDir;

    protected static final int DEFAULT_QUERY_RETRY_INTERVAL = 20;
    protected static final int DEFAULT_QUERY_RETRY_LIMIT = 2;

    protected int retryCount = 0;
    protected boolean failOnError = true;

    private static final Logger LOG = Logger.getLogger(AbstractTask.class.getName());
    private static final String AT_URI = " at URI: ";

    @Override
    public void setContentSourcePool(ContentSourcePool csp) {
        this.csp = csp;
    }

    @Override
    public void setModuleType(String moduleType) {
        this.moduleType = moduleType;
    }

    @Override
    public void setModuleURI(String moduleUri) {
        this.moduleUri = moduleUri;
    }

    @Override
    public void setAdhocQuery(String adhocQuery) {
        this.adhocQuery = adhocQuery;
    }

    @Override
    public void setQueryLanguage(String language) {
        this.language = language;
    }

    @Override
    public void setTimeZone(TimeZone timeZone) {
        this.timeZone = timeZone;
    }

    @Override
    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    @Override
    public void setInputURI(String... inputUri) {
        this.inputUris = inputUri != null ? inputUri.clone() : new String[]{};
    }

    @Override
    public void setFailOnError(boolean failOnError) {
        this.failOnError = failOnError;
    }

    @Override
    public void setExportDir(String exportFileDir) {
        this.exportDir = exportFileDir;
    }

    public String getExportDir() {
        return this.exportDir;
    }

    public Session newSession() throws CorbException{
        return csp.get().newSession();
    }

    @Override
    public String[] call() throws Exception {
        try {
            return invokeModule();
        } finally {
            cleanup();
        }
    }

    protected String[] invokeModule() throws CorbException {
        if (moduleUri == null && adhocQuery == null) {
            return new String[0];
        }

        ResultSequence seq = null;
        Thread.yield();// try to avoid thread starvation
        try (Session session = newSession()) {

            Request request = generateRequest(session);
            //This is how the long running uris can be populated
            Thread.currentThread().setName(asString(inputUris));

            Thread.yield();// try to avoid thread starvation
            seq = session.submitRequest(request);
            retryCount = 0;
            // no need to hold on to the session as results will be cached.
            session.close();
            Thread.yield();// try to avoid thread starvation

            processResult(seq);
            seq.close();
            Thread.yield();// try to avoid thread starvation

            return inputUris;
        } catch (RequestException exc) {
            return handleRequestException(exc);
        } catch (Exception exc) {
            throw new CorbException(exc.getMessage() + AT_URI + asString(inputUris), exc);
        } finally {
            if (null != seq && !seq.isClosed()) {
                seq.close();
                seq = null;
            }
            Thread.yield();// try to avoid thread starvation
        }
    }

    protected Request generateRequest(Session session) throws CorbException {
        Request request;
        //determine whether this is an eval or execution of installed module
        if (moduleUri == null) {
            request = session.newAdhocQuery(adhocQuery);
        } else {
            request = session.newModuleInvoke(moduleUri);
        }

        RequestOptions requestOptions = request.getOptions();
        if (language != null) {
            requestOptions.setQueryLanguage(language);
        }
        if (timeZone != null) {
            requestOptions.setTimeZone(timeZone);
        }

        if (inputUris != null && inputUris.length > 0) {
            if (REQUEST_VARIABLE_DOC.equalsIgnoreCase(properties.getProperty(Options.LOADER_VARIABLE))) {
                setDocRequestVariable(request, inputUris);
            } else {
                setUriRequestVariable(request, inputUris);
            }
        }

        if (properties != null && properties.containsKey(URIS_BATCH_REF)) {
            request.setNewStringVariable(URIS_BATCH_REF, properties.getProperty(URIS_BATCH_REF));
        }

        //set custom inputs
        for (String customInputPropertyName : getCustomInputPropertyNames()) {
            String varName = customInputPropertyName.substring(moduleType.length() + 1);
            String value = getProperty(customInputPropertyName);
            if (value != null) {
                request.setNewStringVariable(varName, value);
            }
        }
        return request;
    }

    protected void setUriRequestVariable(Request request, String... inputUris) {
        String delim = getBatchUriDelimiter();
        String uriValue = StringUtils.join(inputUris, delim);
        request.setNewStringVariable(REQUEST_VARIABLE_URI, uriValue);
    }

    protected void setDocRequestVariable(Request request, String... inputUris) throws CorbException {
        String batchSize = properties.getProperty(Options.BATCH_SIZE);
        //XCC does not allow sequences for request parameters
        if (batchSize != null && Integer.parseInt(batchSize) > 1) {
            throw new CorbException("Cannot set BATCH-SIZE > 1 with REQUEST-VARIABLE-DOC. XCC does not allow sequences for request parameters.");
        }
        XdmItem[] xdmItems = toXdmItems(inputUris);

        XName name = new XName(REQUEST_VARIABLE_DOC);
        request.setVariable(ValueFactory.newVariable(name, xdmItems[0]));
    }

    protected XdmItem[] toXdmItems(String... inputUris) throws CorbException {
        List<XdmItem> docs = new ArrayList<>(inputUris.length);
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            for (String input : inputUris) {
                XdmItem doc = toXdmItem(builder, input);
                docs.add(doc);
            }
        } catch (ParserConfigurationException ex) {
            throw new CorbException("Unable to parse loader document", ex);
        }
        return docs.toArray(new XdmItem[docs.size()]);
    }

    protected XdmItem toXdmItem(DocumentBuilder builder, String input) throws CorbException {
        String normalizedInput = StringUtils.trimToEmpty(input);
        XdmItem doc;
        //it appears to be XML, let's see if we can parse it
        if (normalizedInput.startsWith("<") && normalizedInput.endsWith(">")) {
            try {
                InputSource is = new InputSource(new StringReader(normalizedInput));
                Document dom = builder.parse(is);
                doc = ValueFactory.newDocumentNode(dom);
            } catch (SAXException | IOException ex) {
                LOG.log(WARNING, "Unable to parse URI as XML. Setting content as text.", ex);
                //guess not, lets just use it as-is
                doc = ValueFactory.newDocumentNode(input);
            }
            /**
             * TODO if we support JSON values, then we may need Jackson
             * Databinding added as a dependency
             *
             * } else if (normalizedInput.startsWith("{") &&
             * normalizedInput.endsWith("}")) { //smells like a JSON object
             * XdmItem item = ValueFactory.newJSObject(normalizedInput); doc =
             * ValueFactory.newDocumentNode(item); } else if
             * (normalizedInput.startsWith("[") &&
             * normalizedInput.endsWith("]")) { //smells like a JSON array
             * XdmItem item = ValueFactory.newJSArray(normalizedInput); doc =
             * ValueFactory.newDocumentNode(item);
             */
        } else {
            //assume that it is just plain text, use the original value
            doc = ValueFactory.newDocumentNode(input);
        }
        return doc;
    }

    protected Set<String> getCustomInputPropertyNames() {
        Set<String> moduleCustomInputPropertyNames = new HashSet<>();
        if (moduleType == null) {
            return moduleCustomInputPropertyNames;
        }
        if (properties != null) {
            for (String propName : properties.stringPropertyNames()) {
                if (propName.startsWith(moduleType + '.')) {
                    moduleCustomInputPropertyNames.add(propName);
                }
            }
        }
        for (String propName : System.getProperties().stringPropertyNames()) {
            if (propName.startsWith(moduleType + '.')) {
                moduleCustomInputPropertyNames.add(propName);
            }
        }
        return moduleCustomInputPropertyNames;
    }

    protected boolean shouldRetry(RequestException requestException) {
        return requestException instanceof RetryableQueryException
                || requestException instanceof RequestPermissionException && shouldRetry((RequestPermissionException) requestException)
                || requestException instanceof QueryException && shouldRetry((QueryException) requestException)
                || hasRetryableMessage(requestException);
    }

    protected boolean hasRetryableMessage(RequestException requestException) {
        String message = requestException.getMessage();
        List<String> retryableMessages = commaSeparatedValuesToList(getProperty(QUERY_RETRY_ERROR_MESSAGE));
        for (String messageFragment : retryableMessages) {
            if (message.contains(messageFragment)) {
                return true;
            }
        }
        return false;
    }

    protected boolean shouldRetry(QueryException queryException) {
        String errorCode = queryException.getCode();
        List<String> retryableErrorCodes = commaSeparatedValuesToList(getProperty(QUERY_RETRY_ERROR_CODES));
        return queryException.isRetryable() || retryableErrorCodes.contains(errorCode);
    }

    protected boolean shouldRetry(RequestPermissionException requestPermissionException) {
        return requestPermissionException.isRetryAdvised();
    }

    protected String[] handleRequestException(RequestException requestException) throws CorbException {
        String name = requestException.getClass().getSimpleName();

        if(requestException instanceof ServerConnectionException) {
        		Thread.currentThread().setName(FAILED_URI_TOKEN + Thread.currentThread().getName());
            throw new CorbException(requestException.getMessage() + AT_URI + asString(inputUris), requestException);
        } else if (shouldRetry(requestException)) {
            int retryLimit = this.getQueryRetryLimit();
            int retryInterval = this.getQueryRetryInterval();
            if (retryCount < retryLimit) {
                retryCount++;
                LOG.log(WARNING,
                        "Encountered " + name + " from Marklogic Server. Retrying attempt {0} after {1} seconds..: {2}{3}{4}",
                        new Object[]{retryCount, retryInterval, requestException.getMessage(), AT_URI, asString(inputUris)});
                try {
                    Thread.sleep(retryInterval * 1000L);
                } catch (Exception ex) {
                }
                return invokeModule();
            } else if (failOnError) {
                Thread.currentThread().setName(FAILED_URI_TOKEN + Thread.currentThread().getName());
                throw new CorbException(requestException.getMessage() + AT_URI + asString(inputUris), requestException);
            } else {
                LOG.log(WARNING, failOnErrorIsFalseMessage(name, inputUris), requestException);
                writeToErrorFile(inputUris, requestException.getMessage());
                return inputUris;
            }
        } else if (failOnError) {
            Thread.currentThread().setName(FAILED_URI_TOKEN + Thread.currentThread().getName());
            throw new CorbException(requestException.getMessage() + AT_URI + asString(inputUris), requestException);
        } else {
            LOG.log(WARNING, failOnErrorIsFalseMessage(name, inputUris), requestException);
            writeToErrorFile(inputUris, requestException.getMessage());
            Thread.currentThread().setName(FAILED_URI_TOKEN + Thread.currentThread().getName());
            return inputUris;
        }
    }

    private String failOnErrorIsFalseMessage(final String name, final String... inputUris) {
        return "failOnError is false. Encountered " + name + AT_URI + asString(inputUris);
    }

    protected String asString(String... uris) {
        return uris == null ? "" : StringUtils.join(uris, ",");
    }

    protected abstract String processResult(ResultSequence seq) throws CorbException;

    protected void cleanup() {
        // release resources
        csp = null;
        moduleType = null;
        moduleUri = null;
        properties = null;
        inputUris = null;
        adhocQuery = null;
        language = null;
        timeZone = null;
        exportDir = null;
    }

    public String getProperty(String key) {
        String val = System.getProperty(key);
        if (val == null && properties != null) {
            val = properties.getProperty(key);
        }
        return trim(val);
    }

    protected static byte[] getValueAsBytes(XdmItem item) {
        if (item instanceof XdmBinary) {
            return ((XdmBinary) item).asBinaryData();
        } else if (item != null) {
            return item.asString().getBytes();
        } else {
            return EMPTY_BYTE_ARRAY.clone();
        }
    }

    private int getQueryRetryLimit() {
        int queryRetryLimit = getIntProperty(QUERY_RETRY_LIMIT);
        return queryRetryLimit < 0 ? DEFAULT_QUERY_RETRY_LIMIT : queryRetryLimit;
    }

    private int getQueryRetryInterval() {
        int queryRetryInterval = getIntProperty(QUERY_RETRY_INTERVAL);
        return queryRetryInterval < 0 ? DEFAULT_QUERY_RETRY_INTERVAL : queryRetryInterval;
    }

    private String getBatchUriDelimiter() {
        String delim = getProperty(BATCH_URI_DELIM);
        if (isEmpty(delim)) {
            delim = DEFAULT_BATCH_URI_DELIM;
        }
        return delim;
    }

    /**
     * Retrieves an int value.
     *
     * @param key The key name.
     * @return The requested value ({@code -1} if not found or could not parse
     * value as int).
     */
    protected int getIntProperty(String key) {
        int intVal = -1;
        String value = getProperty(key);
        if (isNotEmpty(value)) {
            try {
                intVal = Integer.parseInt(value);
            } catch (Exception exc) {
                LOG.log(WARNING, MessageFormat.format("Unable to parse `{0}` value `{1}` as an int", key, value), exc);
            }
        }
        return intVal;
    }

    private void writeToErrorFile(String[] uris, String message) {
        if (uris == null || uris.length == 0) {
            return;
        }

        String errorFileName = getProperty(ERROR_FILE_NAME);
        if (isEmpty(errorFileName)) {
            return;
        }

        String delim = getProperty(BATCH_URI_DELIM);
        if (isEmpty(delim)) {
            delim = DEFAULT_BATCH_URI_DELIM;
        }

        synchronized (ERROR_SYNC_OBJ) {
            try (OutputStream writer = new BufferedOutputStream(new FileOutputStream(new File(exportDir, errorFileName), true))) {
                for (String uri : uris) {
                    writer.write(uri.getBytes());
                    if (isNotEmpty(message)) {
                        writer.write(delim.getBytes());
                        writer.write(message.getBytes());
                    }
                    writer.write(NEWLINE);
                }
                writer.flush();
            } catch (Exception exc) {
                LOG.log(SEVERE, "Problem writing uris to " + ERROR_FILE_NAME, exc);
            }
        }
    }

}
