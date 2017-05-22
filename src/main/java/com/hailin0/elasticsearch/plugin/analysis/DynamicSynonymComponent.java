package com.hailin0.elasticsearch.plugin.analysis;

import org.elasticsearch.index.analysis.AnalysisRegistry;
import org.elasticsearch.indices.IndicesService;

/**
 * 插件生命周期内的组件
 *
 * @author hailin0@yeah.net
 * @date 2017-5-9 22:22
 */
public class DynamicSynonymComponent {

    private AnalysisRegistry analysisRegistry;

    private IndicesService indicesService;


    public AnalysisRegistry getAnalysisRegistry() {
        return analysisRegistry;
    }

    /**
     * 该组件被传递给生命周期内的bean初始化时调用，保存同义词初始化需要用到的analysisRegistry
     *
     * @param analysisRegistry
     */
    public void setAnalysisRegistry(AnalysisRegistry analysisRegistry) {
        this.analysisRegistry = analysisRegistry;
    }

    public void setIndicesService(IndicesService indicesService) {
        this.indicesService = indicesService;
    }

    public IndicesService getIndicesService() {
        return indicesService;
    }

}
