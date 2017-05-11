package com.hailin0.elasticsearch.index.analysis;

import org.apache.lucene.analysis.synonym.SynonymMap;

/**
 * 同义词更新接口
 *
 * @author hailin0@yeah.net
 * @date 2017-5-12 0:30
 */
public interface SynonymDynamicSupport {

    public void update(SynonymMap synonymMap);
}
