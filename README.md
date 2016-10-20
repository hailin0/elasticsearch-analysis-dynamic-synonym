# elasticsearch-analysis-dynamic-synonym
elasticsearch 1.1.2版本的同义词热更新插件，支持本地文件更新，http远程文件更新。


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
              includeIndexs: [test,music]
              excludeIndexs: [authors]
              blankSynonymWord: 1-z-0-0-z,1-z-0-0-z
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
    
    3，设置更新时间频率：interval: 60   单位为秒，可以不写，默认值为60
    
    4，includeIndexs，数组，同义词对那些索引有效，不填相当于不使用此属性，即所有索引都使用同义词
    
    5，excludeIndexs，数组，同义词对那些索引无效，不填相当于不使用此属性，即所有索引都使用同义词
    
    6，blankSynonymWord, 字符串（其实就是一个同义词映射），blankSynonymWord可以不设置，默认值就是1-z-0-0-z,1-z-0-0-z。
    此属性只在includeIndexs或者excludeIndexs不为空时有效。
    
    解释：blankSynonymWord可以不设置，也可以填2个“词”以逗号分割，此属性只在includeIndexs或者
    excludeIndexs不为空时有效，主要在做为不同的索引隔离同义词时使用，为不包含同义词的索引做一个默认词，
    为了不造成其他影响，一般使用无任何意义的词或者不存在的词。
    例如：1-z-0-0-z,1-z-0-0-z  表示将第一个词转换成第二个词，
    第一，很明显1-z-0-0-z不是一个词，所以基本不会出现任何不良影响，
    第二，如果你的词典中定义1-z-0-0-z是一个词，那么将1-z-0-0-z转换成1-z-0-0-z，没有任何改变，所以不会对结果产生改变。
    同义词在索引之间隔离就是通过这样的方式实现的，blankSynonymWord可以不设置，默认值就是1-z-0-0-z,1-z-0-0-z，
    如果在你的词典中存在冲突可以自定义为其他值。
    
    7，必须编码都要求是UTF-8的文本文件

# 参考
 es官网同义词配置页面地址<a href='https://www.elastic.co/guide/en/elasticsearch/reference/1.3/analysis-synonym-tokenfilter.html'>点击</a>
