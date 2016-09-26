package org.elasticsearch.synonym;

import java.io.Reader;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.assistedinject.Assisted;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;
import org.elasticsearch.index.analysis.AnalysisSettingsRequired;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.elasticsearch.index.analysis.TokenizerFactoryFactory;
import org.elasticsearch.index.service.IndexService;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.indices.IndicesLifecycle;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.analysis.IndicesAnalysisService;

/**
 * 
 * 类/接口注释
 * 
 * @author hailin0@yeah.net
 * @createDate 2016年9月24日
 *
 */
@AnalysisSettingsRequired
public class DynamicSynonymTokenFilterFactory extends AbstractTokenFilterFactory {

    public static ESLogger logger = Loggers.getLogger("dynamic-synonym");

    private SynonymMap synonymMap;
    private final boolean ignoreCase;

    private final String indexName;
    private final String synonymsPath;
    private final boolean expand;
    private final String format;
    private final int interval;

    private volatile ScheduledFuture<?> scheduledFuture;
    private Map<DynamicSynonymFilter, Integer> dynamicSynonymFilters = new WeakHashMap<DynamicSynonymFilter, Integer>();

    /**
     * 静态的id生成器
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

    @Inject
    public DynamicSynonymTokenFilterFactory(Index index, @IndexSettings Settings indexSettings,
            Environment env, IndicesAnalysisService indicesAnalysisService,
            Map<String, TokenizerFactoryFactory> tokenizerFactories, @Assisted String name,
            @Assisted Settings settings, IndicesService indicesService) {
        super(index, indexSettings, name, settings);

        this.indexName = index.getName();
        if ((synonymsPath = settings.get("synonyms_path")) == null) {
            throw new ElasticsearchIllegalArgumentException(
                    "dynamic synonym requires either `synonyms` or `synonyms_path` to be configured");
        }

        this.interval = settings.getAsInt("interval", 60);
        this.ignoreCase = settings.getAsBoolean("ignore_case", false);
        this.expand = settings.getAsBoolean("expand", true);
        this.format = settings.get("format");

        String tokenizerName = settings.get("tokenizer", "whitespace");
        TokenizerFactoryFactory tokenizerFactoryFactory = tokenizerFactories.get(tokenizerName);
        if (tokenizerFactoryFactory == null) {
            tokenizerFactoryFactory = indicesAnalysisService.tokenizerFactoryFactory(tokenizerName);
        }
        if (tokenizerFactoryFactory == null) {
            throw new ElasticsearchIllegalArgumentException("failed to find tokenizer ["
                    + tokenizerName + "] for synonym token filter");
        }
        final TokenizerFactory tokenizerFactory = tokenizerFactoryFactory.create(tokenizerName,
                settings);

        Analyzer analyzer = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName, Reader reader) {
                Tokenizer tokenizer = tokenizerFactory == null ? new WhitespaceTokenizer(
                        Lucene.ANALYZER_VERSION, reader) : tokenizerFactory.create(reader);
                TokenStream stream = ignoreCase ? new LowerCaseFilter(Lucene.ANALYZER_VERSION,
                        tokenizer) : tokenizer;
                return new TokenStreamComponents(tokenizer, stream);
            }
        };

        SynonymFile synonymFile;
        if (synonymsPath.startsWith("http://")) {
            synonymFile = new RemoteSynonymFile(env, analyzer, expand, format, synonymsPath);
        } else {
            synonymFile = new LocalSynonymFile(env, analyzer, expand, format, synonymsPath);
        }
        synonymMap = synonymFile.reloadSynonymMap();

        // 加入后台线程监控
        scheduledFuture = monitorPool.scheduleAtFixedRate(new Monitor(synonymFile), interval,
                interval, TimeUnit.SECONDS);
        indicesService.indicesLifecycle().addListener(new IndicesLifecycle.Listener() {
            @Override
            public void beforeIndexClosed(IndexService indexService) {
                logger.warn("index:{} close...", indexService.index().getName());
                if (indexService.index().getName().equals(indexName)) {
                    scheduledFuture.cancel(false);
                }
            }
        });

        logger.warn("index:{} init...", indexName);
    }

    /**
     * 每个索引下最多创建8个TokenStream，即create方法会调用8次
     */
    @Override
    public TokenStream create(TokenStream tokenStream) {
        logger.warn("index:{} TokenStream create filter count:{} init...", indexName,
                dynamicSynonymFilters.size());
        DynamicSynonymFilter dynamicSynonymFilter = new DynamicSynonymFilter(tokenStream,
                synonymMap, ignoreCase);
        dynamicSynonymFilters.put(dynamicSynonymFilter, 1);
        // fst is null means no synonyms
        return synonymMap.fst == null ? tokenStream : dynamicSynonymFilter;
    }

    /**
     * 监控逻辑
     * 
     * @author hailin0@yeah.net
     * @createDate 2016年9月24日
     *
     */
    public class Monitor implements Runnable {

        private SynonymFile synonymFile;

        public Monitor(SynonymFile synonymFile) {
            this.synonymFile = synonymFile;
            logger.warn("index:{} Monitor file:{} init...", indexName, synonymFile.getFile());
        }

        @Override
        public void run() {
            try{
                if (synonymFile.isNeedReloadSynonymMap()) {
                    synonymMap = synonymFile.reloadSynonymMap();
                    if(synonymMap == null){
                        return;
                    }
                    /**
                     * @see org.elasticsearch.synonym.DynamicSynonymTokenFilterFactory#create(org.apache.lucene.analysis.TokenStream)
                     *      每个索引下最多创建8个TokenStream，即create方法最多会调用8次<br>
                     *      当synonymMap更新时依次对8个DynamicSynonymFilter对象进行更新
                     */
                    for (DynamicSynonymFilter dynamicSynonymFilter : dynamicSynonymFilters.keySet()) {
                        dynamicSynonymFilter.update(synonymMap);
                        logger.info("index:{} success reload synonym", indexName);
                    }
                }
            }catch(Exception e){
                logger.error("Monitor thread reload remote synonym {} error!", e, synonymFile.getFile());
            }
        }
    }

}