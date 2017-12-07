package org.eclipse.dirigible.repository.search;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.eclipse.dirigible.commons.config.Configuration;
import org.eclipse.dirigible.repository.api.IRepository;
import org.eclipse.dirigible.repository.api.IResource;
import org.eclipse.dirigible.repository.api.RepositoryReadException;
import org.eclipse.dirigible.repository.api.RepositoryWriteException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RepositorySearcher {

	private static final Logger logger = LoggerFactory.getLogger(RepositorySearcher.class);

	/** The Constant DIRIGIBLE_REPOSITORY_SEARCH_ROOT_FOLDER. */
	public static final String DIRIGIBLE_REPOSITORY_SEARCH_ROOT_FOLDER = "DIRIGIBLE_REPOSITORY_SEARCH_ROOT_FOLDER"; //$NON-NLS-1$

	/** The Constant DIRIGIBLE_REPOSITORY_SEARCH_ROOT_FOLDER_IS_ABSOLUTE. */
	public static final String DIRIGIBLE_REPOSITORY_SEARCH_ROOT_FOLDER_IS_ABSOLUTE = "DIRIGIBLE_REPOSITORY_SEARCH_ROOT_FOLDER_IS_ABSOLUTE"; //$NON-NLS-1$

	/** The Constant DIRIGIBLE_REPOSITORY_SEARCH_INDEX_LOCATION. */
	public static final String DIRIGIBLE_REPOSITORY_SEARCH_INDEX_LOCATION = "DIRIGIBLE_REPOSITORY_SEARCH_INDEX_LOCATION"; //$NON-NLS-1$

	private static final String CURRENT_DIR = ".";
	private static final String CURRENT_INDEX = "dirigible" + IRepository.SEPARATOR + "repository"
			+ IRepository.SEPARATOR + "index";

	private static final String FIELD_CONTENTS = "contents";
	private static final String FIELD_MODIFIED = "modified";
	private static final String FIELD_LOCATION = "location";

	private static final int MAX_RESULTS = 1000;

	private IRepository repository;

	private String root;

	private String index;

	private Timer timer;

	private int seconds = 10;

	private List<String> synchronizedPaths = new ArrayList<String>();

	private Date lastUpdated = new Date(0);

	private volatile int countUpdated = 0;

	public RepositorySearcher(IRepository repository) {
		this.repository = repository;

		Configuration.load("/dirigible-repository-search.properties");
		String rootFolder = Configuration.get(RepositorySearcher.DIRIGIBLE_REPOSITORY_SEARCH_ROOT_FOLDER);
		boolean absolute = Boolean.parseBoolean(
				Configuration.get(RepositorySearcher.DIRIGIBLE_REPOSITORY_SEARCH_ROOT_FOLDER_IS_ABSOLUTE));
		String indexLocation = Configuration.get(RepositorySearcher.DIRIGIBLE_REPOSITORY_SEARCH_INDEX_LOCATION,
				CURRENT_INDEX);

		if (absolute) {
			if (rootFolder != null) {
				this.root = rootFolder;
			} else {
				throw new IllegalStateException(
						"Creating a Repository Searcher with absolute path flag, but the path itself is null");
			}
		} else {
			this.root = System.getProperty("user.dir");
			if ((rootFolder != null) && !rootFolder.equals(CURRENT_DIR)) {
				this.root += File.separator;
				this.root += rootFolder;
			}
		}

		this.index = indexLocation;

		timer = new Timer();
		timer.schedule(new ReindexTask(), 30000, seconds * 1000);
	}

	class ReindexTask extends TimerTask {
		@Override
		public void run() {
			synchronized (synchronizedPaths) {
				if (countUpdated > 30) {
					countUpdated = 0;
					lastUpdated = new Date(0);
					logger.debug("Full reindexing of the Repository Content...");
				}
				reindex();
				lastUpdated = new Date();
				countUpdated++;
			}
		}
	}

	public void add(String location, byte[] contents, long lastModified, Map<String, String> parameters)
			throws RepositoryWriteException {
		String indexName = index;

		try {
			Directory dir = FSDirectory.open(Paths.get(root + File.separator + indexName));
			Analyzer analyzer = new StandardAnalyzer();
			IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
			iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
			IndexWriter writer = null;
			try {
				writer = new IndexWriter(dir, iwc);
				Document doc = new Document();
				Field pathField = new StringField(FIELD_LOCATION, location, Field.Store.YES);
				doc.add(pathField);
				doc.add(new LongPoint(FIELD_MODIFIED, lastModified));
				if (parameters != null) {
					for (String key : parameters.keySet()) {
						doc.add(new StringField(key, parameters.get(key), Field.Store.YES));
					}
				}
				doc.add(new TextField(FIELD_CONTENTS, new BufferedReader(
						new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
				writer.updateDocument(new Term(FIELD_LOCATION, location), doc);
			} finally {
				if (writer != null) {
					writer.close();
				}
			}
		} catch (IOException e) {
			throw new RepositoryWriteException(e);
		}
	}

	public List<String> search(String term) throws RepositoryReadException {
		List<String> results = new ArrayList<String>();
		String indexName = index;

		try {
			Directory dir = FSDirectory.open(Paths.get(root + File.separator + indexName));
			IndexReader reader = null;
			try {
				reader = DirectoryReader.open(dir);
				IndexSearcher searcher = new IndexSearcher(reader);
				Analyzer analyzer = new StandardAnalyzer();
				String field = FIELD_CONTENTS;
				QueryParser parser = new QueryParser(field, analyzer);
				Query query = parser.parse(term);
				TopDocs topDocs = searcher.search(query, MAX_RESULTS);
				for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
					Document document = searcher.doc(scoreDoc.doc);
					for (IndexableField indexableField : document.getFields()) {
						String name = indexableField.name();
						if (FIELD_LOCATION.equals(name)) {
							String value = indexableField.stringValue();
							results.add(value);
							break;
						}
					}

				}
			} finally {
				if (reader != null) {
					reader.close();
				}
			}
			return results;
		} catch (IOException | ParseException e) {
			throw new RepositoryReadException(e);
		}
	}

	private void reindex() {
		long start = System.currentTimeMillis();
		List<String> paths = repository.getAllResourcePaths();
		for (String path : paths) {
			IResource resource = repository.getResource(path);
			if ((resource != null) && (resource.getInformation() != null)
					&& (resource.getInformation().getModifiedAt() != null)) {
				if (lastUpdated.before(resource.getInformation().getModifiedAt())) {
					add(path, resource.getContent(), resource.getInformation().getModifiedAt().getTime(), null);
				}
			}
		}
		long end = System.currentTimeMillis();
		logger.trace("Reindexing of the Repository Content finished in: " + (end - start) + "ms");
	}

	public void forceReindex() {
		this.lastUpdated = new Date(0);
		this.countUpdated = 0;
		reindex();
	}

}
