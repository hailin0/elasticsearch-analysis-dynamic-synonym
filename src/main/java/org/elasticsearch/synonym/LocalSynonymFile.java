/**
 * 
 */
package org.elasticsearch.synonym;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Locale;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.synonym.WordnetSynonymParser;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.common.base.Charsets;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.FailedToResolveConfigException;

/**
 * 本地文件
 * 
 * @author hailin0@yeah.net
 * @createDate 2016年9月24日
 *
 */
public class LocalSynonymFile implements SynonymFile {

    public static ESLogger logger = Loggers.getLogger("dynamic-synonym");

    private String format;

    private boolean expand;

    private Analyzer analyzer;

    private Environment env;

    /** 本地文件路径 相对于config目录 */
    private String synonymFilePath;

    private File synonymFile;

    /** 上次更改时间 */
    private long lastModified;

    public LocalSynonymFile(Environment env, Analyzer analyzer, boolean expand, String format,
            String synonymFilePath) {
        this.analyzer = analyzer;
        this.expand = expand;
        this.format = format;
        this.env = env;

        this.synonymFilePath = synonymFilePath;
        try {
            this.synonymFile = new File(env.resolveConfig(synonymFilePath).toURI());
        } catch (Exception e) {
            throw new ElasticsearchIllegalArgumentException(
                    "synonym requires either  `synonyms_path` to be configured");
        }
        isNeedReloadSynonymMap();
    }

    @Override
    public SynonymMap reloadSynonymMap() {
        try {
            logger.info("start reload local synonym from {}.", synonymFilePath);
            Reader rulesReader = getReader();
            SynonymMap.Builder parser = null;
            if ("wordnet".equalsIgnoreCase(format)) {
                parser = new WordnetSynonymParser(true, expand, analyzer);
                ((WordnetSynonymParser) parser).parse(rulesReader);
            } else {
                parser = new SolrSynonymParser(true, expand, analyzer);
                ((SolrSynonymParser) parser).parse(rulesReader);
            }
            return parser.build();
        } catch (Exception e) {
            logger.error("reload local synonym {} error!", e, synonymFilePath);
            throw new IllegalArgumentException(
                    "could not reload local synonyms file to build synonyms", e);
        }

    }

    @Override
    public Reader getReader() {
        Reader reader = null;
        BufferedReader br = null;
        try {

            // URL fileUrl = env.resolveConfig(synonymFilePath);
            try {
                reader = new InputStreamReader(new FileInputStream(synonymFile), Charsets.UTF_8);
            } catch (IOException ioe) {
                String message = String.format(Locale.ROOT,
                        "IOException while reading %s_path: %s", synonymFilePath, ioe.getMessage());
                throw new ElasticsearchIllegalArgumentException(message);
            }

            // br = new BufferedReader(new InputStreamReader(synonymFileURL.openStream(),
            // Charsets.UTF_8));
            // StringBuffer sb = new StringBuffer("");
            // String line = null;
            // while ((line = br.readLine()) != null) {
            // logger.info("reload local synonym: {}", line);
            // sb.append(line).append(System.getProperty("line.separator"));
            // }
            // reader = new FastStringReader(sb.toString());

        } catch (Exception e) {
            logger.error("get local synonym reader {} error!", e, synonymFilePath);
            throw new IllegalArgumentException("IOException while reading local synonyms file", e);
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return reader;
    }

    @Override
    public boolean isNeedReloadSynonymMap() {
        try {
            if (synonymFile.exists() && lastModified < synonymFile.lastModified()) {
                lastModified = synonymFile.lastModified();
                return true;
            }
        } catch (Exception e) {
            logger.error("check need reload local synonym {} error!", e, synonymFilePath);
        }

        return false;
    }

    /* (non-Javadoc)
     * @see org.elasticsearch.synonym.SynonymFile#getPath()
     */
    @Override
    public String getFile() {
        return synonymFilePath;
    }

}
