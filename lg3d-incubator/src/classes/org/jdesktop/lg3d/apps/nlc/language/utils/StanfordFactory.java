/**
 * This code was written during my participation in Google's Summer
 * of Code 2005. Thanks to Google for bringing out such a program
 * and there commitment to open-source.
 */
package org.jdesktop.lg3d.apps.nlc.language.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.logging.Logger;

import edu.stanford.nlp.parser.lexparser.LexicalizedParser;

public class StanfordFactory {
	
	private static Logger logger = Logger.getLogger("lg.nlc");
	
	private LexicalizedParser parser;
	
	private final static String DEFAULT_PARSER_FILE = "etc/lg3d/englishPCFG.ser.gz";

	// The grammar model ships inside the lg3d-incubator jar next to the nlc
	// classes. The legacy code expected it to have been unpacked into
	// <lg.etcdir>/lg3d/englishPCFG.ser.gz, which the Gradle port does not do, so
	// resolve it from the classpath instead.
	private final static String BUNDLED_PARSER_RESOURCE =
		"org/jdesktop/lg3d/apps/nlc/conf/englishPCFG.ser.gz";

	private StanfordFactory(String parserFile) throws IOException{
		String file = resolveParserFile(parserFile);
		logger.info("Loading parser from " + file); 
		parser = new LexicalizedParser(file);
	}

	// Locate the serialized grammar model. Prefer an on-disk copy under
	// lg.etcdir (the legacy install location, so a user-supplied model still
	// wins); otherwise fall back to the copy bundled in the jar. Because
	// LexicalizedParser reads from a filesystem path and the bundled model lives
	// inside a jar, the resource is copied to a temporary file.
	private static String resolveParserFile(String parserFile) throws IOException {
		String etcDir = System.getProperty("lg.etcdir");
		if (etcDir != null) {
			File etcFile = new File(etcDir, "lg3d/englishPCFG.ser.gz");
			if (etcFile.isFile()) {
				return etcFile.getAbsolutePath();
			}
		}
		URL res = StanfordFactory.class.getClassLoader()
			.getResource(BUNDLED_PARSER_RESOURCE);
		if (res == null) {
			throw new IOException("Stanford NLP grammar model not found: "
				+ BUNDLED_PARSER_RESOURCE + " (requested: " + parserFile + ")");
		}
		return copyToTempFile(res);
	}

	private static String copyToTempFile(URL res) throws IOException {
		File tmp = File.createTempFile("englishPCFG", ".ser.gz");
		tmp.deleteOnExit();
		try (InputStream in = res.openStream();
		     OutputStream out = new FileOutputStream(tmp)) {
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
			}
		}
		return tmp.getAbsolutePath();
	}
	
	private static StanfordFactory instance;
	
	public static StanfordFactory getInstance(String parserFile) throws IOException{
		if (parserFile == null) {
			throw new NullPointerException(
					"Argument to method getInstance is null");
		}
		if (instance == null){
			instance = new StanfordFactory(parserFile);
		}
		return instance;
	}
	
	public static StanfordFactory getInstance() throws IOException{
		return getInstance(DEFAULT_PARSER_FILE);
	}
	
	public LexicalizedParser getParser(){
		return parser;
	}
	
	public static void unload(){
		logger.info("Unloading the parser");
		instance = null;
		//FIXME Ask hideya whether we should run gc here ?
	}
}
