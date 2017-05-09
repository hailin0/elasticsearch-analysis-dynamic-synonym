package com.hailin0.elasticsearch.plugin.analysis;

import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.analysis.AnalysisRegistry;

/**
 * 插件生命周期内的服务,通过@Inject注解被es初始化，传递需要的Component进来
 *
 * @author hailin0@yeah.net
 * @date 2017-5-9 22:27
 */
public class DynamicSynonymGuiceService extends AbstractLifecycleComponent {

    @Inject
    public DynamicSynonymGuiceService(final Settings settings, final AnalysisRegistry analysisRegistry,
                                      final DynamicSynonymComponent pluginComponent) {
        super(settings);
        pluginComponent.setAnalysisRegistry(analysisRegistry);
    }

    @Override
    protected void doStart() {
    }

    @Override
    protected void doStop() {
    }

    @Override
    protected void doClose() {
    }
}
