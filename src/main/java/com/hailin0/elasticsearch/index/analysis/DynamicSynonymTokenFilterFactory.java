package com.hailin0.elasticsearch.index.analysis;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;
import org.elasticsearch.index.analysis.AnalysisRegistry;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.elasticsearch.indices.analysis.AnalysisModule;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 类注释/描述
 *
 * @author hailin0@yeah.net
 * @date 2017-5-9 22:34
 */
public class DynamicSynonymTokenFilterFactory extends
        AbstractTokenFilterFactory {

    public static Logger logger = ESLoggerFactory.getLogger("dynamic-synonym");


    //配置属性
    private final String indexName;
    private final String location;
    private final boolean ignoreCase;
    private final boolean expand;
    private final String format;
    private final int interval;


    /**
     * 每个过滤器实例产生的资源-index级别
     */
    private SynonymMap synonymMap;
    private volatile ScheduledFuture<?> scheduledFuture;
    private CopyOnWriteArrayList<DynamicSynonymFilter> dynamicSynonymFilters = new CopyOnWriteArrayList<DynamicSynonymFilter>();

    /**
     * load调度器-node级别
     */
    private static final AtomicInteger id = new AtomicInteger(1);
    private static ScheduledExecutorService monitorPool = Executors.newScheduledThreadPool(1,
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r);
                    thread.setName("monitor-synonym-Thread-" + id.getAndAdd(1));
                    return thread;
                }
            });


    public DynamicSynonymTokenFilterFactory(IndexSettings indexSettings, Environment env, AnalysisRegistry analysisRegistry,
                                            String name, Settings settings) throws IOException {

        //加载配置
        super(indexSettings, name, settings);
        this.indexName = indexSettings.getIndex().getName();
        this.interval = settings.getAsInt("interval", 60);
        this.ignoreCase = settings.getAsBoolean("ignore_case", false);
        this.expand = settings.getAsBoolean("expand", true);
        this.format = settings.get("format", "");
        this.location = settings.get("synonyms_path");

        logger.info("indexName:{} synonyms_path:{} interval:{} ignore_case:{} expand:{} format:{}",
                indexName, location, interval, ignoreCase, expand, format);

        //属性检查
        if (this.location == null) {
            throw new IllegalArgumentException(
                    "dynamic synonym requires `synonyms_path` to be configured");
        }

        String tokenizerName = settings.get("tokenizer", "whitespace");
        AnalysisModule.AnalysisProvider<TokenizerFactory> tokenizerFactoryFactory =
                analysisRegistry.getTokenizerProvider(tokenizerName, indexSettings);
        if (tokenizerFactoryFactory == null) {
            throw new IllegalArgumentException("failed to find tokenizer [" + tokenizerName + "] for synonym token filter");
        }
        final TokenizerFactory tokenizerFactory = tokenizerFactoryFactory.get(indexSettings, env, tokenizerName,
                AnalysisRegistry.getSettingsFromIndexSettings(indexSettings, AnalysisRegistry.INDEX_ANALYSIS_TOKENIZER + "." + tokenizerName));
        Analyzer analyzer = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = tokenizerFactory == null ? new WhitespaceTokenizer() : tokenizerFactory.create();
                TokenStream stream = ignoreCase ? new LowerCaseFilter(tokenizer) : tokenizer;
                return new TokenStreamComponents(tokenizer, stream);
            }
        };


        //根据location前缀初始化同义词更新策略
        SynonymFile synonymFile;
        if (location.startsWith("http://")) {
            synonymFile = new RemoteSynonymFile(env, analyzer, expand, format, location);
        } else {
            synonymFile = new LocalSynonymFile(env, analyzer, expand, format, location);
        }
        synonymMap = synonymFile.reloadSynonymMap();

        //加入监控队列，定时load
        scheduledFuture = monitorPool.scheduleAtFixedRate(new Monitor(synonymFile),
                interval, interval, TimeUnit.SECONDS);
    }

    /**
     * 每个索引下创建n个TokenStream，即create方法会调用多次，此方法有并发，被多线程调用
     */
    @Override
    public TokenStream create(TokenStream tokenStream) {
        DynamicSynonymFilter dynamicSynonymFilter = new DynamicSynonymFilter(
                tokenStream, synonymMap, ignoreCase);
        dynamicSynonymFilters.add(dynamicSynonymFilter);

        // fst is null means no synonyms
        return synonymMap.fst == null ? tokenStream : dynamicSynonymFilter;
    }


    /**
     * 监控逻辑
     *
     * @author hailin0@yeah.net
     * @createDate 2016年9月24日
     */
    public class Monitor implements Runnable {

        private SynonymFile synonymFile;

        public Monitor(SynonymFile synonymFile) {
            this.synonymFile = synonymFile;
        }

        @Override
        public void run() {
            try {
                if (synonymFile.isNeedReloadSynonymMap()) {
                    SynonymMap newSynonymMap = synonymFile.reloadSynonymMap();
                    if (newSynonymMap == null || newSynonymMap.fst == null) {
                        logger.error("Monitor thread reload remote synonym non-null! indexName:{} path:{}",
                                indexName, synonymFile.getLocation());
                        return;
                    }
                    synonymMap = newSynonymMap;
                    Iterator<DynamicSynonymFilter> filters = dynamicSynonymFilters.iterator();
                    while (filters.hasNext()) {
                        filters.next().update(synonymMap);
                        logger.info("success reload synonym success! indexName:{} path:{}", indexName, synonymFile.getLocation());
                    }
                }
            } catch (Exception e) {
                logger.error("Monitor thread reload remote synonym error! indexName:{} path:{}",
                        indexName, synonymFile.getLocation());
            }
        }
    }

}
