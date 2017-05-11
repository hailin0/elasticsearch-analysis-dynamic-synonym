package com.hailin0.elasticsearch.index.analysis;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
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
public class DynamicSynonymGraphTokenFilterFactory extends
        DynamicSynonymTokenFilterFactory {

    public DynamicSynonymGraphTokenFilterFactory(IndexSettings indexSettings, Environment env, AnalysisRegistry analysisRegistry,
                                                 String name, Settings settings) throws IOException {
        super(indexSettings,env,analysisRegistry,name,settings);
    }

    /**
     * 每个索引下创建n个TokenStream，即create方法会调用多次，此方法有并发，被多线程调用
     */
    @Override
    public TokenStream create(TokenStream tokenStream) {
        DynamicSynonymGraphFilter dynamicSynonymGraphFilter = new DynamicSynonymGraphFilter(
                tokenStream, synonymMap, ignoreCase);
        dynamicSynonymFilters.add(dynamicSynonymGraphFilter);

        // fst is null means no synonyms
        return synonymMap.fst == null ? tokenStream : dynamicSynonymGraphFilter;
    }

}
