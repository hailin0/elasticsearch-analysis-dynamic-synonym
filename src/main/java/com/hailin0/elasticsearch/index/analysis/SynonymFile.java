/**
 * 
 */
package com.hailin0.elasticsearch.index.analysis;

import org.apache.lucene.analysis.synonym.SynonymMap;

import java.io.Reader;

public interface SynonymFile {
	
	public SynonymMap reloadSynonymMap();

	public boolean isNeedReloadSynonymMap();
	
	public Reader getReader();

	public  String getLocation();

}