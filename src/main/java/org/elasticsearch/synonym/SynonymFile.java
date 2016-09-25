/**
 * 
 */
package org.elasticsearch.synonym;

import java.io.Reader;

import org.apache.lucene.analysis.synonym.SynonymMap;

/**
 * 
 * 同义词文件访问接口
 * 
 * @author hailin0@yeah.net
 * @createDate 2016年9月24日
 *
 */
public interface SynonymFile {

    SynonymMap reloadSynonymMap();

    boolean isNeedReloadSynonymMap();

    Reader getReader();
    
    String getFile();

}