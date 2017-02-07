/**
 * 
 */
package com.bellszhu.elasticsearch.plugin;

import com.bellszhu.elasticsearch.plugin.analysis.DynamicSynonymTokenFilterFactory;
import org.elasticsearch.index.analysis.AnalysisModule;
import org.elasticsearch.plugins.Plugin;

/**
 * 插件入口
 *
 * wanghailin@gomechengdu.com
 *
 */
public class DynamicSynonymPlugin extends Plugin {

	@Override
	public String description() {
		return "Analysis-plugin for synonym";
	}

	@Override
	public String name() {
		return "analysis-dynamic-synonym";
	}
	
	public void onModule(AnalysisModule module) {
        module.addTokenFilter("dynamic_synonym", DynamicSynonymTokenFilterFactory.class);
    }

}
