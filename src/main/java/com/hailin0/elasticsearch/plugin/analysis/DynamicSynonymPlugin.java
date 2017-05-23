/**
 *
 */
package com.hailin0.elasticsearch.plugin.analysis;

import com.hailin0.elasticsearch.index.analysis.DynamicSynonymGraphTokenFilterFactory;
import com.hailin0.elasticsearch.index.analysis.DynamicSynonymTokenFilterFactory;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.indices.analysis.AnalysisModule;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;

import java.io.IOException;
import java.util.*;

import static java.util.Collections.singletonList;

/**
 * 插件入口
 * DynamicSynonymComponent
 */
public class DynamicSynonymPlugin extends Plugin implements AnalysisPlugin {

    public static Logger logger = ESLoggerFactory.getLogger("dynamic-synonym");

    private DynamicSynonymComponent pluginComponent = new DynamicSynonymComponent();


    //1，创建组件
    @Override
    public Collection<Object> createComponents(Client client,
                                               ClusterService clusterService,
                                               ThreadPool threadPool,
                                               ResourceWatcherService resourceWatcherService,
                                               ScriptService scriptService,
                                               NamedXContentRegistry xContentRegistry) {
        Collection<Object> components = new ArrayList<>();
        components.add(pluginComponent);
        return components;
    }


    //2，注入1创建的组件给目标类,在目标类中初始化
    @Override
    public Collection<Class<? extends LifecycleComponent>> getGuiceServiceClasses() {
        List<Class<DynamicSynonymGuiceService>> a = singletonList(DynamicSynonymGuiceService.class);
        Object b = a;
        return (Collection<Class<? extends LifecycleComponent>>) b;
    }

    //3.添加lister
    public void onIndexModule(IndexModule indexModule) {
        indexModule.addIndexEventListener(new DynamicSynonymIndexEventListener());
    }

    //4.释放资源
    public void close() {
        logger.info("DynamicSynonymPlugin close...");
        DynamicSynonymTokenFilterFactory.closeDynamicSynonym();
    }

    //5.加载插件
    @Override
    public Map<String, AnalysisModule.AnalysisProvider<TokenFilterFactory>> getTokenFilters() {
        Map<String, AnalysisModule.AnalysisProvider<TokenFilterFactory>> tokenFilters = new HashMap<>();
        tokenFilters.put("dynamic_synonym", new AnalysisModule.AnalysisProvider<TokenFilterFactory>() {
            @Override
            public TokenFilterFactory get(IndexSettings indexSettings, Environment environment, String name, Settings settings)
                    throws IOException {
                return new DynamicSynonymTokenFilterFactory(indexSettings, environment, pluginComponent.getAnalysisRegistry(), name, settings);
            }
            @Override
            public boolean requiresAnalysisSettings() {
                return true;
            }
        });
        tokenFilters.put("dynamic_synonym_graph", new AnalysisModule.AnalysisProvider<TokenFilterFactory>() {
            @Override
            public TokenFilterFactory get(IndexSettings indexSettings, Environment environment, String name, Settings settings)
                    throws IOException {
                return new DynamicSynonymGraphTokenFilterFactory(indexSettings, environment, pluginComponent.getAnalysisRegistry(), name, settings);
            }
            @Override
            public boolean requiresAnalysisSettings() {
                return true;
            }
        });
        return tokenFilters;
    }


}
