package com.hailin0.elasticsearch.plugin.analysis;

import com.hailin0.elasticsearch.index.analysis.DynamicSynonymTokenFilterFactory;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.IndexEventListener;

/**
 * 索引事件监听
 *
 * @author hailin0@yeah.net
 * @date 2017-5-22 0:36
 */
public class DynamicSynonymIndexEventListener implements IndexEventListener {

    public static Logger logger = ESLoggerFactory.getLogger("dynamic-synonym");

    /**
     * 索引删除事件
     * @param indexService
     */
    @Override
    public void beforeIndexDeleted(IndexService indexService) {
        logger.info("beforeIndexDeleted ! indexName:{}",indexService.index().getName());
        DynamicSynonymTokenFilterFactory.closeIndDynamicSynonym(indexService.index().getName());
    }

    /**
     * 索引关闭事件
     * @param indexService
     */
    @Override
    public void beforeIndexClosed(IndexService indexService) {
        logger.info("beforeIndexClosed ! indexName:{}",indexService.index().getName());
        DynamicSynonymTokenFilterFactory.closeIndDynamicSynonym(indexService.index().getName());
    }

}
