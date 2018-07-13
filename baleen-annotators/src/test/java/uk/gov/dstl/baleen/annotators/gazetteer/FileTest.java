//Dstl (c) Crown Copyright 2017
package uk.gov.dstl.baleen.annotators.gazetteer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.fit.factory.AnalysisEngineFactory;
import org.apache.uima.fit.factory.ExternalResourceFactory;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.resource.ExternalResourceDescription;
import org.junit.Test;

import uk.gov.dstl.baleen.annotators.gazetteer.File;
import uk.gov.dstl.baleen.annotators.testing.AnnotatorTestBase;
import uk.gov.dstl.baleen.resources.SharedFileResource;
import uk.gov.dstl.baleen.types.language.Text;
import uk.gov.dstl.baleen.types.semantic.Location;
import uk.gov.dstl.baleen.types.semantic.ReferenceTarget;

public class FileTest extends AnnotatorTestBase{

	private static String WORLD = "world";
	private static String NEW_YORK = "New York";
	private static String LOCATION = "Location";
	private static String TYPE = "type";
	private static String GAZETTEER_TXT = "gazetteer.txt";
	private static String FILE_NAME = "fileName";
	private static String FILE_GAZETTEER = "fileGazetteer";


	@Test
	public void test() throws Exception{
		ExternalResourceDescription erd = ExternalResourceFactory.createExternalResourceDescription(FILE_GAZETTEER, SharedFileResource.class);
		AnalysisEngineDescription aed = AnalysisEngineFactory.createEngineDescription(File.class, FILE_GAZETTEER, erd, FILE_NAME, getClass().getResource(GAZETTEER_TXT).getPath(), TYPE, LOCATION);

		AnalysisEngine ae = AnalysisEngineFactory.createEngine(aed);

		jCas.setDocumentText("Hello world, this is a test");

		ae.process(jCas);

		assertEquals(1, JCasUtil.select(jCas, Location.class).size());
		Location l = JCasUtil.selectByIndex(jCas, Location.class, 0);
		assertEquals(WORLD, l.getValue());
		assertEquals(WORLD, l.getCoveredText());

		ae.destroy();
	}


	@Test
	public void testmultipleHits() throws Exception{

		ExternalResourceDescription erd = ExternalResourceFactory.createExternalResourceDescription(FILE_GAZETTEER, SharedFileResource.class);
		AnalysisEngineDescription aed = AnalysisEngineFactory.createEngineDescription(File.class, FILE_GAZETTEER, erd, FILE_NAME, getClass().getResource(GAZETTEER_TXT).getPath(), TYPE, LOCATION);

		AnalysisEngine ae = AnalysisEngineFactory.createEngine(aed);

		// the same search term appears multiple times in text...
		jCas.setDocumentText("Hello world, and hello world again.");

		ae.process(jCas);

		assertEquals(2, JCasUtil.select(jCas, Location.class).size());
		Location l = JCasUtil.selectByIndex(jCas, Location.class, 0);
		assertEquals(WORLD, l.getValue());
		assertEquals(WORLD, l.getCoveredText());

		ae.destroy();
	}	

	@Test
	public void testCaseSensitive() throws Exception{
		ExternalResourceDescription erd = ExternalResourceFactory.createExternalResourceDescription(FILE_GAZETTEER, SharedFileResource.class);
		AnalysisEngineDescription aed = AnalysisEngineFactory.createEngineDescription(File.class, FILE_GAZETTEER, erd, FILE_NAME, getClass().getResource(GAZETTEER_TXT).getPath(), TYPE, LOCATION, "caseSensitive", true);

		AnalysisEngine ae = AnalysisEngineFactory.createEngine(aed);

		jCas.setDocumentText("This text mentions New York and Paris in upper case and new york in lower case");

		ae.process(jCas);

		// should match "new york" and "Paris", but not "New York"
		assertEquals(2, JCasUtil.select(jCas, Location.class).size());
		Location l1 = JCasUtil.selectByIndex(jCas, Location.class, 0);
		Location l2 = JCasUtil.selectByIndex(jCas, Location.class, 1);
		assertEquals("Paris", l1.getValue());
		assertEquals("new york", l2.getValue());

		ae.destroy();
	}
	
	@Test
	public void testPlurals() throws Exception{
		//This test demonstrates pluralisation in the gazetteer

		ExternalResourceDescription erd = ExternalResourceFactory.createExternalResourceDescription(FILE_GAZETTEER, SharedFileResource.class);
		AnalysisEngineDescription aed = AnalysisEngineFactory.createEngineDescription(File.class, FILE_GAZETTEER, erd, FILE_NAME, getClass().getResource(GAZETTEER_TXT).getPath(), TYPE, LOCATION, "plural", true);

		AnalysisEngine ae = AnalysisEngineFactory.createEngine(aed);

		jCas.setDocumentText("There may be many New Yorks and many Parises, but there's only one London.");

		ae.process(jCas);

		assertEquals(3, JCasUtil.select(jCas, Location.class).size());
		Location l1 = JCasUtil.selectByIndex(jCas, Location.class, 0);
		Location l2 = JCasUtil.selectByIndex(jCas, Location.class, 1);
		Location l3 = JCasUtil.selectByIndex(jCas, Location.class, 2);
		assertEquals("New Yorks", l1.getValue());
		assertEquals("Parises", l2.getValue());
		assertEquals("London", l3.getValue());

		ae.destroy();
	}


	@Test
	public void testWhitespaceExact() throws Exception{
		//This test demonstrates the case where whitespace is preserved in gazetteer matching.

		ExternalResourceDescription erd = ExternalResourceFactory.createExternalResourceDescription(FILE_GAZETTEER, SharedFileResource.class);
		AnalysisEngineDescription aed = AnalysisEngineFactory.createEngineDescription(File.class, FILE_GAZETTEER, erd, FILE_NAME, getClass().getResource(GAZETTEER_TXT).getPath(), TYPE, LOCATION);

		AnalysisEngine ae = AnalysisEngineFactory.createEngine(aed);

		// words in term to search for separated by multiple spaces, tabs or newline...
		jCas.setDocumentText("This text mentions New York, and New    York again, and New	York again, and New \nYork yet again");

		ae.process(jCas);

		// only one mention of "New York" has the two words separated by a single space (as in the gazetteer)
		assertEquals(1, JCasUtil.select(jCas, Location.class).size());
		Location l = JCasUtil.selectByIndex(jCas, Location.class, 0);
		assertEquals(NEW_YORK, l.getValue());

		ae.destroy();
	}

	@Test
	public void testWhitespaceNormalized() throws Exception{
		//This test demonstrates the case where whitespace is preserved in gazetteer matching.

		ExternalResourceDescription erd = ExternalResourceFactory.createExternalResourceDescription(FILE_GAZETTEER, SharedFileResource.class);
		AnalysisEngineDescription aed = AnalysisEngineFactory.createEngineDescription(File.class, FILE_GAZETTEER, erd, FILE_NAME, getClass().getResource(GAZETTEER_TXT).getPath(), TYPE, LOCATION, "exactWhitespace", false);

		AnalysisEngine ae = AnalysisEngineFactory.createEngine(aed);

		// words in term to search for separated by multiple spaces, tabs or newline...
		jCas.setDocumentText("This text mentions New York, and New    York again, and New	York again, and New \nYork yet again");

		ae.process(jCas);

		// Three mentions of "New York" if we reduce any whitespace to a single space (exactWhitespace parameter, which ignores new lines)
		assertEquals(3, JCasUtil.select(jCas, Location.class).size());
		Location l = JCasUtil.selectByIndex(jCas, Location.class, 0);
		assertEquals(NEW_YORK, l.getValue());

		ae.destroy();
	}


	@Test
	public void testReference() throws Exception{
		//This test demonstrates the case where whitespace is preserved in gazetteer matching.

		ExternalResourceDescription erd = ExternalResourceFactory.createExternalResourceDescription(FILE_GAZETTEER, SharedFileResource.class);
		AnalysisEngineDescription aed = AnalysisEngineFactory.createEngineDescription(File.class, FILE_GAZETTEER, erd, FILE_NAME, getClass().getResource(GAZETTEER_TXT).getPath(), TYPE, LOCATION, "exactWhitespace", false);

		AnalysisEngine ae = AnalysisEngineFactory.createEngine(aed);

		// words in term to search for separated by multiple spaces, tabs or newline...
		jCas.setDocumentText("This text mentions New York (also known as NY and the Big Apple).");

		ae.process(jCas);

		// 3 mentions of "New York" and nicknames...
		assertEquals(3, JCasUtil.select(jCas, Location.class).size());
		// ...but they're all the same entity, so only one ReferenceTarget
		assertEquals(1, JCasUtil.select(jCas, ReferenceTarget .class).size());

		Location l = JCasUtil.selectByIndex(jCas, Location.class, 0);
		assertEquals(NEW_YORK, l.getValue());

		ae.destroy();
	}

	@Test
	public void testmultipleHitsWithText() throws Exception{

		ExternalResourceDescription erd = ExternalResourceFactory.createExternalResourceDescription(FILE_GAZETTEER, SharedFileResource.class);
		AnalysisEngineDescription aed = AnalysisEngineFactory.createEngineDescription(File.class, FILE_GAZETTEER, erd, FILE_NAME, getClass().getResource(GAZETTEER_TXT).getPath(), TYPE, LOCATION);

		AnalysisEngine ae = AnalysisEngineFactory.createEngine(aed);

		// the same search term appears multiple times in text...
		jCas.setDocumentText("Hello world, and hello world again.");
		// but then subset using a Text annotation
		new Text(jCas, 10, jCas.getDocumentText().length()).addToIndexes();

		ae.process(jCas);

		assertEquals(1, JCasUtil.select(jCas, Location.class).size());
		Location l = JCasUtil.selectByIndex(jCas, Location.class, 0);
		assertEquals(WORLD, l.getValue());
		assertEquals(WORLD, l.getCoveredText());
		assertTrue(l.getBegin() > 10);

		ae.destroy();
	}   



}
