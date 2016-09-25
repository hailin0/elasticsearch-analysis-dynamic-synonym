package org.elasticsearch.plugin;

import org.elasticsearch.common.inject.Module;
import org.elasticsearch.index.analysis.AnalysisModule;
import org.elasticsearch.plugins.AbstractPlugin;
import org.elasticsearch.synonym.DynamicSynonymTokenFilterFactory;

/**
 * 
 * 插件入口
 * 
 * @author hailin0@yeah.net
 * @createDate 2016年9月24日
 *
 */
public class DynamicSynonymPlugin extends AbstractPlugin {

    public String name() {
        return "dynamic-synonym";
    }

    public String description() {
        return "Analysis-plugin for dynamic-synonym";
    }

    @Override
    public void processModule(Module module) {
        if (module instanceof AnalysisModule) {
            AnalysisModule analysisModule = (AnalysisModule) module;
            analysisModule
                    .addTokenFilter("dynamic_synonym", DynamicSynonymTokenFilterFactory.class);
        }
    }
}