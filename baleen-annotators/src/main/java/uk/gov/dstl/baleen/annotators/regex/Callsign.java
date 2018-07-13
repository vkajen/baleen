//Dstl (c) Crown Copyright 2017
package uk.gov.dstl.baleen.annotators.regex;

import java.util.Collections;
import java.util.regex.Matcher;

import org.apache.uima.jcas.JCas;

import com.google.common.collect.ImmutableSet;

import uk.gov.dstl.baleen.annotators.regex.helpers.AbstractRegexNPAnnotator;
import uk.gov.dstl.baleen.core.pipelines.orderers.AnalysisEngineAction;
import uk.gov.dstl.baleen.types.common.Person;

/**
 * Annotate callsigns using a regular expression and phrase chunking where available
 * 
 * <p>Noun phrases in the document are compared against the following regular expression, with matches being annotated as a person.</p>
 * <pre>\\bC[\\\\|/]S [A-Z ]+\\b</pre>
 * 
 * 
 */
public class Callsign extends AbstractRegexNPAnnotator<Person> {
	
	public static final String REGEX_CS = "\\bC[\\\\|/]S [A-Z ]+\\b";

	/** New instance.
	 * 
	 */
	public Callsign() {
		super(REGEX_CS, true, 1.0);
	}

	@Override
	protected Person create(JCas jCas, Matcher matcher) {
		return new Person(jCas);
	}
	
	@Override
	public AnalysisEngineAction getAction() {
		return new AnalysisEngineAction(Collections.emptySet(), ImmutableSet.of(Person.class));
	}
}
