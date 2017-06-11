/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.elasticsearch

import groovy.transform.CompileStatic
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.util.ReadOnlyStringMap
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest
import org.elasticsearch.action.bulk.BulkRequestBuilder
import org.elasticsearch.action.bulk.BulkResponse
import org.elasticsearch.client.Client
import org.elasticsearch.transport.TransportException
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.LogEventSubscriber
import org.moqui.context.ToolFactory
import org.moqui.impl.context.ExecutionContextFactoryImpl

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

@CompileStatic
class ElasticSearchLoggerToolFactory implements ToolFactory<LogEventSubscriber> {
    final static String TOOL_NAME = "ElasticSearchLogger"
    // TODO: make this configurable somehow
    final static String INDEX_NAME = "moqui_logs"
    final static String DOC_TYPE = "LogMessage"

    protected ExecutionContextFactoryImpl ecfi = null
    protected ElasticSearchSubscriber subscriber = null

    private Client elasticSearchClient = null
    private boolean disabled = false
    final ConcurrentLinkedQueue<Map> logMessageQueue = new ConcurrentLinkedQueue<>()

    /** Default empty constructor */
    ElasticSearchLoggerToolFactory() { }

    @Override String getName() { return TOOL_NAME }
    @Override void init(ExecutionContextFactory ecf) {
        ecfi = (ExecutionContextFactoryImpl) ecf

        elasticSearchClient = ecfi.getTool("ElasticSearch", Client.class)
        if (elasticSearchClient == null) {
            System.err.println("In ElasticSearchLogger init could not find ElasticSearch tool")
        } else {
            // check for index exists, create with mapping for log doc if not
            boolean hasIndex = elasticSearchClient.admin().indices().exists(new IndicesExistsRequest(INDEX_NAME)).actionGet().exists
            if (!hasIndex) {
                CreateIndexRequestBuilder cirb = elasticSearchClient.admin().indices().prepareCreate(INDEX_NAME)
                cirb.addMapping(DOC_TYPE, docMapping)
                cirb.execute().actionGet()
            }

            subscriber = new ElasticSearchSubscriber(this)
            ecfi.registerLogEventSubscriber(subscriber)

            LogMessageQueueFlush lmqf = new LogMessageQueueFlush(this)
            ecfi.scheduledExecutor.scheduleAtFixedRate(lmqf, 2, 1, TimeUnit.SECONDS)
        }

    }
    @Override void preFacadeInit(ExecutionContextFactory ecf) { }

    @Override LogEventSubscriber getInstance(Object... parameters) { return subscriber }
    @Override void destroy() {
        disabled = true
    }

    static class ElasticSearchSubscriber implements LogEventSubscriber {
        private final ElasticSearchLoggerToolFactory factory
        private final InetAddress localAddr = InetAddress.getLocalHost()

        ElasticSearchSubscriber(ElasticSearchLoggerToolFactory factory) {
            this.factory = factory
        }
        @Override
        void process(LogEvent event) {
            if (factory.disabled || factory.elasticSearchClient == null) return
            // NOTE: levels configurable in log4j2.xml but always exclude these
            if (Level.DEBUG.is(event.level) || Level.TRACE.is(event.level)) return

            Map<String, Object> msgMap = ['@timestamp':event.timeMillis, level:event.level.toString(), thread_name:event.threadName,
                    thread_id:event.threadId, thread_priority:event.threadPriority, logger_name:event.loggerName,
                    message:event.message?.formattedMessage, source_host:localAddr.hostName] as Map<String, Object>
            ReadOnlyStringMap contextData = event.contextData
            if (contextData != null && contextData.size() > 0) {
                Map<String, String> mdcMap = new HashMap<>(contextData.toMap())
                String userId = mdcMap.get("moqui_userId")
                if (userId != null) { msgMap.put("user_id", userId); mdcMap.remove("moqui_userId") }
                String visitorId = mdcMap.get("moqui_visitorId")
                if (visitorId != null) { msgMap.put("visitor_id", visitorId); mdcMap.remove("moqui_visitorId") }
                if (mdcMap.size() > 0) msgMap.put("mdc", mdcMap)
                // System.out.println("Cur user ${userId} ${visitorId}")
            }
            Throwable thrown = event.thrown
            if (thrown != null) msgMap.put("thrown", makeThrowableMap(thrown))

            factory.logMessageQueue.add(msgMap)
        }
        static Map makeThrowableMap(Throwable thrown) {
            StackTraceElement[] stArray = thrown.stackTrace
            List<String> stList = []
            for (int i = 0; i < stArray.length; i++) {
                StackTraceElement ste = (StackTraceElement) stArray[i]
                stList.add("${ste.className}.${ste.methodName}(${ste.fileName}:${ste.lineNumber})".toString())
            }
            Map<String, Object> thrownMap = [name:thrown.class.name, message:thrown.message,
                    localizedMessage:thrown.localizedMessage, stackTrace:stList] as Map<String, Object>
            Throwable cause = thrown.cause
            if (cause != null) thrownMap.put("cause", makeThrowableMap(cause))
            Throwable[] supArray = thrown.suppressed
            if (supArray != null && supArray.length > 0) {
                List<Map> supList = []
                for (int i = 0; i < supArray.length; i++) {
                    Throwable sup = supArray[i]
                    supList.add(makeThrowableMap(sup))
                }
                thrownMap.put("suppressed", supList)
            }
            return thrownMap
        }
    }

    static class LogMessageQueueFlush implements Runnable {
        final static int maxCreates = 50
        final ElasticSearchLoggerToolFactory factory

        LogMessageQueueFlush(ElasticSearchLoggerToolFactory factory) { this.factory = factory }

        @Override synchronized void run() {
            while (factory.logMessageQueue.size() > 0) {
                flushQueue()
            }
        }
        void flushQueue() {
            final ConcurrentLinkedQueue<Map> queue = factory.logMessageQueue
            ArrayList<Map> createList = new ArrayList<>(maxCreates)
            int createCount = 0
            while (createCount < maxCreates) {
                Map message = queue.poll()
                if (message == null) break
                createCount++
                createList.add(message)
            }
            int retryCount = 5
            while (retryCount > 0) {
                int createListSize = createList.size()
                if (createListSize == 0) break
                try {
                    long startTime = System.currentTimeMillis()
                    try {
                        BulkRequestBuilder bulkBuilder = factory.elasticSearchClient.prepareBulk()
                        for (int i = 0; i < createListSize; i++) {
                            Map curMessage = createList.get(i)
                            // System.out.println(curMessage.toString())
                            bulkBuilder.add(factory.elasticSearchClient.prepareIndex(INDEX_NAME, DOC_TYPE, null)
                                    .setSource(curMessage))
                        }
                        BulkResponse bulkResponse = bulkBuilder.execute().actionGet()
                        if (bulkResponse.hasFailures()) {
                            System.out.println(bulkResponse.buildFailureMessage())
                        }
                    } catch (TransportException te) {
                        String message = te.getMessage()
                        if (message && message.toLowerCase().contains("stopped")) factory.disabled = true
                        System.out.println("Stopping ElasticSearch logging, transport error: ${te.toString()}")
                    } catch (Exception e) {
                        System.out.println("Error logging to ElasticSearch: ${e.toString()}")
                    }
                    // System.out.println("Indexed ${createListSize} ElasticSearch log messages in ${System.currentTimeMillis() - startTime}ms")
                    break
                } catch (Throwable t) {
                    System.out.println("Error indexing ElasticSearch log messages, retrying (${retryCount}): ${t.toString()}")
                    retryCount--
                }
            }
        }
    }

    final static Map stackItemMapping = [type:'object', properties:[class:[type:'text'], method:[type:'text'], file:[type:'text'],
            line:[type:'long']]]
    final static Map docMapping = [properties:
            ['@timestamp':[type:'date', format:'epoch_millis'], level:[type:'keyword'], thread_name:[type:'keyword'],
                    thread_id:[type:'long'], thread_priority:[type:'long'], user_id:[type:'keyword'], visitor_id:[type:'keyword'],
                    logger_name:[type:'text'], name:[type:'text'], message:[type:'text'], mdc:[type:'object'],
                    thrown:[type:'object', properties:[name:[type:'text'], message:[type:'text'], localizedMessage:[type:'text'],
                            stackTrace:[type:'text'],
                            suppressed:[type:'object', properties:[name:[type:'text'], message:[type:'text'], localizedMessage:[type:'text'],
                                    commonElementCount:[type:'long'], stackTrace:[type:'text']]],
                            cause:[type:'object', properties:[name:[type:'text'], message:[type:'text'], localizedMessage:[type:'text'],
                                    commonElementCount:[type:'long'], stackTrace:[type:'text']]]
            ]]
    ]]
}
