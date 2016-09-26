# elasticsearch-analysis-dynamic-synonym
es1.1.2版本的同义词热更新插件，支持本地文件更新，http远程文件更新。


#使用方法
    1，部署插件：将doc/plugins/dynamic-synonym 目录放到 ${es-root}/plugins下
    2，配置使用：
    index:
      analysis:
        filter:
          local_synonym:
              type: dynamic_synonym
              synonyms_path: dynamic-synonym/synonym.txt
              interval: 60
          remote_synonym:
              type: dynamic_synonym
              synonyms_path: http://127.0.0.1:8080/es-service-control/LoadWord/remote_ext_synonym.txt
              interval: 60
        analyzer:
          ik:
              alias: [ik_analyzer]
              type: org.elasticsearch.index.analysis.IkAnalyzerProvider
          ik_max_word:
              type: ik
              use_smart: false
          ik_smart:
              type: ik
              use_smart: true
          ik_syno:
              type: custom
              tokenizer: ik
              filter: [local_synonym,remote_synonym]
          ik_syno_smart:
              type: custom
              tokenizer: ik
              filter: [local_synonym,remote_synonym]
              use_smart: true
          standard_syno:
              type: custom
              filter: [local_synonym,remote_synonym]
              tokenizer: standard
    index.analysis.analyzer.default.type : "ik"


# 说明
    1，配置普通本地文件，设置相对目录下的文件，相对于${es-root}/config，
      更新依据为文件的最后修改时间（Modify time）变化。
      例子：synonyms_path: dynamic-synonym/synonym.txt  
    
    2，配置远程文件，以http开头即可，更新依据为2个http响应头发生变化，
      一个是 Last-Modified，一个是 ETag，任意一个变化都会更新。
      例子：synonyms_path: http://127.0.0.1:8080/es-service-control/LoadWord/remote_ext_synonym.txt
    
    3，设置更新时间频率：interval: 60   单位为秒
    
    4，必须编码都要求是UTF-8的文本文件
