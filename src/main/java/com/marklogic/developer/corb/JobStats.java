package com.marklogic.developer.corb;

import static com.marklogic.developer.corb.util.StringUtils.isJavaScriptModule;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import com.marklogic.developer.corb.util.FileUtils;
import org.w3c.dom.*;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSSerializer;

import com.marklogic.developer.corb.util.StringUtils;
import com.marklogic.xcc.AdhocQuery;
import com.marklogic.xcc.ContentSource;
import com.marklogic.xcc.Request;
import com.marklogic.xcc.RequestOptions;
import com.marklogic.xcc.ResultSequence;
import com.marklogic.xcc.Session;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class JobStats extends BaseMonitor {

    private static final String NOT_APPLICABLE = "NA";
    private static final long TPS_ETC_MIN_REFRESH_INTERVAL = 10000l;
    private static final String METRICS_COLLECTIONS_PARAM = "collections";
    private static final String METRICS_DOCUMENT_STR_PARAM = "metricsDocumentStr";
    private static final String METRICS_DB_NAME_PARAM = "dbName";
    private static final String METRICS_URI_ROOT_PARAM = "uriRoot";
    protected static final String XQUERY_VERSION_ML = "xquery version \"1.0-ml\";\n";
    private static final String XDMP_LOG_FORMAT = "xdmp:log('%1$s','%2$s')";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    private static final String START_TIME = "startTime";
    private static final String URI = "uri";
    private static final String JOB_ID = "id";
    private static final String JOB_NAME = "name";
    public static final String JOB_ELEMENT = "job";
    public static final String JOBS_ELEMENT = "jobs";
    public static final String CORB_NAMESPACE = "http://marklogic.github.io/corb/";
    private static final String LONG_RUNNING_URIS = "slowTransactions";
    private static final String FAILED_URIS = "failedTransactions";
    private static final String URIS_LOAD_TIME = "urisLoadTimeInMillis";
    private static final String INIT_TASK_TIME = "initTaskTimeInMillis";
    private static final String PRE_BATCH_RUN_TIME = "preBatchRunTimeInMillis";
    private static final String POST_BATCH_RUN_TIME = "postBatchRunTimeInMillis";
    private static final String TOTAL_JOB_RUN_TIME = "totalRunTimeInMillis";
    private static final String AVERAGE_TRANSACTION_TIME = "averageTransactionTimeInMillis";
    private static final String TOTAL_NUMBER_OF_TASKS = "totalNumberOfTasks";
    private static final String NUMBER_OF_FAILED_TASKS = "numberOfFailedTasks";
    private static final String NUMBER_OF_SUCCEEDED_TASKS = "numberOfSucceededTasks";
    private static final String METRICS_DOC_URI = "metricsDocUri";
    private static final String PAUSED = "paused";
    private static final String AVERAGE_TPS = "averageTransactionsPerSecond";
    private static final String CURRENT_TPS = "currentTransactionsPerSecond";
    private static final String ESTIMATED_TIME_OF_COMPLETION = "estimatedTimeOfCompletion";
    private static final String METRICS_TIMESTAMP = "timestamp";

    private static final String HOST = "host";
    private static final String END_TIME = "endTime";
    private static final String USER_PROVIDED_OPTIONS = "userProvidedOptions";
    private static final String JOB_LOCATION = "runLocation";
    private static final String CURRENT_THREAD_COUNT = "currentThreadCount";
    private static final String JOB_SERVER_PORT = "port";

    private Map<String, String> userProvidedOptions = new HashMap<>();
    private String startTime = null;
    private String endTime = null;
    private String host = null;

    private Long numberOfFailedTasks = 0l;
    private Long numberOfSucceededTasks = 0l;
    private Double averageTransactionTime = 0.0d;
    private Long urisLoadTime = -1l;
    private Long preBatchRunTime = -1l;
    private Long postBatchRunTime = -1l;
    private Long initTaskRunTime = -1l;
    private Long totalRunTimeInMillis = -1l;
    private String jobRunLocation = null;
    private String jobId = null;
    private String jobName = null;
    private Map<String, Long> longRunningUris = new HashMap<>();
    private List<String> failedUris = null;
    private String uri = null;
    private boolean paused;
    private Long currentThreadCount = 0l;
    private Long jobServerPort = -1l;

    private ContentSourcePool csp;
    private TransformOptions options;

    protected final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
    private final TransformerFactory transformerFactory  = TransformerFactory.newInstance();
    private Templates jobStatsToJsonTemplates;

    private static final Logger LOG = Logger.getLogger(JobStats.class.getName());

    public JobStats(Manager manager) {
        super(manager);
        options = manager.options;
        csp = manager.getContentSourcePool();
        host = getHost();
        jobRunLocation = System.getProperty("user.dir");
        userProvidedOptions = manager.getUserProvidedOptions();
    }

    protected String getHost() {
        String hostName = "Unknown";
        try {
            InetAddress addr = InetAddress.getLocalHost();
            hostName = addr.getHostAddress();
        } catch (UnknownHostException ex) {
            try {
                hostName = InetAddress.getLoopbackAddress().getHostAddress();
            } catch (Exception e) {
                LOG.log(INFO, "Host address can not be resolved", e);
            }
        }
        return hostName;
    }

    private void refresh() {
        synchronized (this) {

            if (manager != null && manager.monitor != null) {
                jobId = manager.jobId;
                paused = manager.isPaused();

                if (options != null) {
                    jobName = options.getJobName();
                    jobServerPort = options.getJobServerPort().longValue();
                }

                startTime = epochMillisAsFormattedDateString(manager.getStartMillis());

                taskCount = manager.monitor.getTaskCount();
                if (taskCount > 0) {

                    PausableThreadPoolExecutor threadPool = manager.monitor.pool;
                    longRunningUris = threadPool.getTopUris();
                    failedUris = threadPool.getFailedUris();
                    numberOfFailedTasks = Integer.toUnsignedLong(threadPool.getNumFailedUris());
                    numberOfSucceededTasks = Integer.toUnsignedLong(threadPool.getNumSucceededUris());

                    Long currentTimeMillis = System.currentTimeMillis();
                    Long totalTime = manager.getEndMillis() - manager.getStartMillis();
                    if (totalTime > 0) {
                        currentThreadCount = 0l;
                        totalRunTimeInMillis = totalTime;
                        long totalTransformTime = currentTimeMillis - manager.getTransformStartMillis();
                        averageTransactionTime = totalTransformTime / Double.valueOf(numberOfFailedTasks) + Double.valueOf(numberOfSucceededTasks);
                        endTime = epochMillisAsFormattedDateString(manager.getEndMillis());
                        estimatedTimeOfCompletion = null;
                    } else {

                        currentThreadCount = Long.valueOf(options.getThreadCount());
                        totalRunTimeInMillis = currentTimeMillis - manager.getStartMillis();

                        long timeSinceLastReq = currentTimeMillis - prevMillis;
                        //refresh it every 10 seconds or more.. ignore more frequent requests
                        if (timeSinceLastReq > TPS_ETC_MIN_REFRESH_INTERVAL) {
                            long completed = numberOfSucceededTasks + numberOfFailedTasks;
                            populateTps(completed);
                        }
                    }
                }
            }
        }
    }

    protected static String epochMillisAsFormattedDateString(long epochMillis) {
        LocalDateTime date = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
        return date.format(DATE_FORMATTER);
    }

    public void logToServer(String message, boolean concise) {
        String processModule = options.getMetricsModule();
        Document doc = toXML(concise);
        String metricsLogMessage = toJSON(doc);
        String metricsDocument;
        if (isJavaScriptModule(processModule)) {
            metricsDocument = metricsLogMessage;
        } else {
            metricsDocument = toXmlString(doc);
        }
        executeModule(metricsDocument);

        logToServer(message, metricsLogMessage);
    }

    private void logToServer(String message, String metrics) {
    		String logLevel = options.getLogMetricsToServerLog();
        if (csp != null && options.isMetricsLoggingEnabled(logLevel)) {
        		Session session =  null;
        		try {
        			ContentSource contentSource = csp.get();
        			if(contentSource != null) {
        				session = contentSource.newSession();
        				String xquery = XQUERY_VERSION_ML
                                + (message != null
                                        ? String.format(XDMP_LOG_FORMAT, message, logLevel.toLowerCase()) + ','
                                        : "")
                                + String.format(XDMP_LOG_FORMAT, metrics, logLevel.toLowerCase());

                    AdhocQuery query = session.newAdhocQuery(xquery);
                    session.submitRequest(query);
        			}else {
        				LOG.log(SEVERE, "logJobStatsToServer request failed. ContentSourcePool.get() returned null");
        			}
            } catch (Exception e) {
                LOG.log(SEVERE, "logJobStatsToServer request failed", e);
            }finally {
            		if(session != null) {
            			session.close();
            		}
            }
        }
    }

    private void executeModule(String metrics) {
        String metricsDatabase = options.getMetricsDatabase();
        if (metricsDatabase != null) {
            String uriRoot = options.getMetricsRoot();

            ResultSequence seq = null;
            RequestOptions requestOptions = new RequestOptions();
            requestOptions.setCacheResult(false);

            String collections = options.getMetricsCollections();
            String processModule = options.getMetricsModule();

            Thread.yield();// try to avoid thread starvation

            if (csp != null) {
            		Session session = null;
                try{
                		ContentSource contentSource = csp.get();
                		if (contentSource != null) {
	                	    session = contentSource.newSession();
	                    Request request = manager.getRequestForModule(processModule, session);
	                    request.setNewStringVariable(METRICS_DB_NAME_PARAM, metricsDatabase);
	                    request.setNewStringVariable(METRICS_URI_ROOT_PARAM, uriRoot != null ? uriRoot : NOT_APPLICABLE);
	                    request.setNewStringVariable(METRICS_COLLECTIONS_PARAM, collections != null ? collections : NOT_APPLICABLE);
	
	                    if (isJavaScriptModule(processModule)) {
	                        requestOptions.setQueryLanguage("javascript");
	                        request.setNewStringVariable(METRICS_DOCUMENT_STR_PARAM,
	                                metrics == null ? toJSON() : metrics);
	                    } else {
	                        request.setNewStringVariable(METRICS_DOCUMENT_STR_PARAM,
	                                metrics == null ? toXmlString() : metrics);
	                    }
	                    request.setOptions(requestOptions);
	
	                    seq = session.submitRequest(request);
	                    String uri = seq.hasNext() ? seq.next().asString() : null;
	                    if (uri != null) {
	                        this.uri = uri;
	                    }
	
	                    Thread.yield();// try to avoid thread starvation
	                    seq.close();
	                    Thread.yield();// try to avoid thread starvation
                		}
                } catch (Exception exc) {
                    LOG.log(SEVERE, "logJobStatsToServerDocument request failed", exc);
                } finally {
                    if (null != seq && !seq.isClosed()) {
                        seq.close();
                        seq = null;
                    }
                    if (session != null) {
                        session.close();
                    }

                    Thread.yield();// try to avoid thread starvation
                }
            }
        }
    }

    @Override
    public String toString() {
        return toString(true);
    }

    public String toString(boolean concise) {
        return toJSON(concise);
    }

    public String toXmlString() {
        return toXmlString(false);
    }

    public String toXmlString(boolean concise) {
        Document doc = toXML(concise);
        return toXmlString(doc);
    }

    public static String toXmlString(Document doc)    {
        DOMImplementationLS domImplementation = (DOMImplementationLS) doc.getImplementation();
        LSSerializer lsSerializer = domImplementation.createLSSerializer();
        return lsSerializer.writeToString(doc);
    }

    public static Document toXML(DocumentBuilderFactory documentBuilderFactory, List<JobStats> jobStatsList, boolean concise) {
        Document doc = null;
        try {
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            doc = documentBuilder.newDocument();
            Element element = doc.createElementNS(CORB_NAMESPACE, JOBS_ELEMENT);
            for (JobStats jobStats : jobStatsList) {
                element.appendChild(jobStats.createJobElement(doc, concise));
            }
            doc.appendChild(element);
        } catch (ParserConfigurationException ex) {
            LOG.log(SEVERE, "Unable to create a new XML Document", ex);
        }
        return doc;
    }

    public Document toXML(boolean concise) {

        refresh();

        Document doc = null;
        try {
            DocumentBuilder docBuilder = documentBuilderFactory.newDocumentBuilder();
            doc = docBuilder.newDocument();
            Element docElement = createJobElement(doc, concise);
            doc.appendChild(docElement);
        } catch (ParserConfigurationException ex) {
            LOG.log(Level.SEVERE, "Unable to create a new XML Document", ex);
        }
        return doc;
    }

    protected Element createJobElement(Document doc, boolean concise) {
        Element element = doc.createElementNS(CORB_NAMESPACE, JOB_ELEMENT);

        createAndAppendElement(element, METRICS_TIMESTAMP, LocalDateTime.now().format(DATE_FORMATTER));
        createAndAppendElement(element, METRICS_DOC_URI, uri);
        createAndAppendElement(element, JOB_LOCATION, jobRunLocation);
        createAndAppendElement(element, JOB_NAME, jobName);
        createAndAppendElement(element, JOB_ID, jobId);
        if (!concise) {
            createAndAppendElement(element, USER_PROVIDED_OPTIONS, userProvidedOptions);
        }
        createAndAppendElement(element, HOST, host);
        createAndAppendElement(element, JOB_SERVER_PORT, jobServerPort);

        createAndAppendElement(element, START_TIME, startTime);
        createAndAppendElement(element, INIT_TASK_TIME, initTaskRunTime);
        createAndAppendElement(element, PRE_BATCH_RUN_TIME, preBatchRunTime);
        createAndAppendElement(element, URIS_LOAD_TIME, urisLoadTime);
        createAndAppendElement(element, POST_BATCH_RUN_TIME, postBatchRunTime);
        createAndAppendElement(element, END_TIME, endTime);
        createAndAppendElement(element, TOTAL_JOB_RUN_TIME, totalRunTimeInMillis);

        createAndAppendElement(element, PAUSED, Boolean.toString(paused));
        createAndAppendElement(element, TOTAL_NUMBER_OF_TASKS, taskCount);
        createAndAppendElement(element, CURRENT_THREAD_COUNT, currentThreadCount);
        createAndAppendElement(element, CURRENT_TPS, currentTps > 0 ? formatTransactionsPerSecond(currentTps) : "");
        createAndAppendElement(element, AVERAGE_TPS, avgTps > 0 ? formatTransactionsPerSecond(avgTps) : "");
        createAndAppendElement(element, AVERAGE_TRANSACTION_TIME, averageTransactionTime);
        createAndAppendElement(element, ESTIMATED_TIME_OF_COMPLETION, estimatedTimeOfCompletion);

        createAndAppendElement(element, NUMBER_OF_SUCCEEDED_TASKS, numberOfSucceededTasks);
        createAndAppendElement(element, NUMBER_OF_FAILED_TASKS, numberOfFailedTasks);
        if (!concise) {
            addLongRunningUris(element);
            addFailedUris(element);
        }
        return element;
    }

    protected void createAndAppendElement(Node parent, String localName, String value) {
        if (StringUtils.isNotEmpty(value)) {
            Element element = createElement(parent, localName, value);
            parent.appendChild(element);
        }
    }

    protected void createAndAppendElement(Node parent, String localName, Long value) {
        if (value != null && value >= 0l) {
            createAndAppendElement(parent, localName, value.toString());
        }
    }

    protected void createAndAppendElement(Node parent, String localName, Double value) {
        if (value != null && value >= 0l) {
            createAndAppendElement(parent, localName, value.toString());
        }
    }

    protected void createAndAppendElement(Node parent, String localName, Map<String, String>value){
        if (value != null && !value.isEmpty()) {
            Document doc = parent.getOwnerDocument();
            Element element = doc.createElementNS(CORB_NAMESPACE, localName);
            for (Map.Entry<String, String> entry : value.entrySet()) {
                createAndAppendElement(element, entry.getKey(), entry.getValue());
            }
            parent.appendChild(element);
        }
    }

    protected Element createElement(Node parent, String localName, String value) {
        Document doc = parent.getOwnerDocument();
        Element element = doc.createElementNS(CORB_NAMESPACE, localName);
        Text text = doc.createTextNode(value);
        element.appendChild(text);
        return element;
    }

    public String toJSON() {
        return toJSON(false);
    }

    public String toJSON(boolean concise) {
        Document doc = toXML(concise);
        return toJSON(doc);
    }

    public static String toJSON(Templates jobStatsToJsonTemplates, Document doc) throws TransformerException {
        StringWriter sw = new StringWriter();
        Transformer autobot = jobStatsToJsonTemplates.newTransformer();
        autobot.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }

    public String toJSON(Document doc) {
        StringBuilder json = new StringBuilder();
        try {
            if (jobStatsToJsonTemplates == null) {
                jobStatsToJsonTemplates = newJobStatsToJsonTemplates(transformerFactory);
            }
            json.append(toJSON(jobStatsToJsonTemplates, doc));
        } catch (TransformerException e) {
            LOG.log(SEVERE, "Unable to transform to JSON", e);
        }
        return json.toString();
    }

    public static Templates newJobStatsToJsonTemplates(TransformerFactory transformerFactory) throws TransformerConfigurationException {
        return newTemplates(transformerFactory, "jobStatsToJson.xsl");
    }
    protected static Templates newTemplates(TransformerFactory transformerFactory, String stylesheetFilename) throws TransformerConfigurationException {
        StreamSource styleSource = new StreamSource(FileUtils.getFile(stylesheetFilename));
        return transformerFactory.newTemplates(styleSource);
    }

    protected void addLongRunningUris(Node parent) {

        if (longRunningUris != null && !longRunningUris.isEmpty()) {
            Document doc = parent.getOwnerDocument();
            Element rankingElement = doc.createElementNS(CORB_NAMESPACE, LONG_RUNNING_URIS);

            NavigableSet<Long> ranks = new TreeSet<>();
            ranks.addAll(longRunningUris.values());
            Map<Integer, List<Element>> rankToXML = new HashMap<>();
            int numUris = longRunningUris.keySet().size();
            for (Map.Entry<String, Long> entry : longRunningUris.entrySet()) {
                Long time = entry.getValue();
                Integer rank = numUris - ranks.headSet(time).size();
                List<Element> urisWithSameRank = rankToXML.get(rank);

                if (urisWithSameRank != null) {
                    urisWithSameRank.add(createElement(rankingElement, URI, entry.getKey()));
                } else {
                    List<Element> rankData = new ArrayList<>();
                    rankData.add(createElement(rankingElement, URI, entry.getKey()));
                    rankData.add(createElement(rankingElement, "rank", rank.toString()));
                    rankData.add(createElement(rankingElement, "timeInMillis", time.toString()));
                    urisWithSameRank = rankData;
                }
                rankToXML.put(rank, urisWithSameRank);
            }

            for (Map.Entry<Integer, List<Element>> entry : rankToXML.entrySet()){
                Element uriElement = doc.createElementNS(CORB_NAMESPACE, "Uri");
                for (Element element : entry.getValue()) {
                    uriElement.appendChild(element);
                }
                rankingElement.appendChild(uriElement);
            }
            parent.appendChild(rankingElement);
        }
    }

    protected void addFailedUris(Node parent) {
        if (failedUris != null && !failedUris.isEmpty()) {
            Document doc = parent.getOwnerDocument();
            Element failedUrisElement = doc.createElementNS(CORB_NAMESPACE, FAILED_URIS);
            for (String nodeVal : failedUris) {
                createAndAppendElement(failedUrisElement, URI, nodeVal);
            }
            parent.appendChild(failedUrisElement);
        }
    }
    /**
     * @param initTaskRunTime the initTaskRunTime to set
     */
    public void setInitTaskRunTime(Long initTaskRunTime) {
        this.initTaskRunTime = initTaskRunTime;
    }

    /**
     * @param preBatchRunTime the preBatchRunTime to set
     */
    public void setPreBatchRunTime(Long preBatchRunTime) {
        this.preBatchRunTime = preBatchRunTime;
    }

    public void setUrisLoadTime(Long urisLoadTime) {
        this.urisLoadTime = urisLoadTime;
    }

    /**
     * @param postBatchRunTime the postBatchRunTime to set
     */
    public void setPostBatchRunTime(Long postBatchRunTime) {
        this.postBatchRunTime = postBatchRunTime;
    }
}
