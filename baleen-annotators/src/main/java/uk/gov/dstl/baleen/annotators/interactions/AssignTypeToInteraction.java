//Dstl (c) Crown Copyright 2017
package uk.gov.dstl.baleen.annotators.interactions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.descriptor.ExternalResource;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;
import org.bson.Document;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.mongodb.client.MongoCollection;

import opennlp.tools.stemmer.snowball.SnowballStemmer;
import opennlp.tools.stemmer.snowball.SnowballStemmer.ALGORITHM;
import uk.gov.dstl.baleen.annotators.patterns.data.InteractionTypeDefinition;
import uk.gov.dstl.baleen.core.pipelines.orderers.AnalysisEngineAction;
import uk.gov.dstl.baleen.resources.SharedMongoResource;
import uk.gov.dstl.baleen.types.language.Interaction;
import uk.gov.dstl.baleen.types.language.WordToken;
import uk.gov.dstl.baleen.uima.BaleenAnnotator;
import uk.gov.dstl.baleen.uima.utils.ComparableEntitySpanUtils;

/**
 * Assign relation type and subtype to interaction.
 *
 * The mongo gazetteers only allow a word to map to to one annotation type (eg "City of London" maps
 * to only one London in the world). For interactions that is not sufficient, we need attack (noun)
 * and attack (verb) to map to potentially different interactions.
 *
 * So for interactions we use the gazetteers to assign the interaction annotators. This annotator
 * then reviews that assignment and:
 * <ul>
 * <li>Removes annotations where the part of speech don't match
 * <li>Adds information to interaction annotation such as the type and subtype
 * <li>Duplicates annotations which have multiple senses
 * </ul>
 *
 * This annotator uses to relationTypes collection generated by {@link UploadInteractionsToMongo}.
 * Note that using this annotator may be optional is you have a very simple set of interaction words
 * (without overlaps, which the gazetteers may cope with).
 *
 * @baleen.javadoc
 *
 */
public class AssignTypeToInteraction extends BaleenAnnotator {
	/**
	 * Connection to Mongo
	 *
	 * @baleen.resource uk.gov.dstl.baleen.resources.SharedMongoResource
	 */
	public static final String KEY_MONGO = "mongo";
	@ExternalResource(key = KEY_MONGO)
	private SharedMongoResource mongo;

	/**
	 * The name of the Mongo collection containing the relation types
	 *
	 * @baleen.config gazetteer
	 */
	public static final String PARAM_COLLECTION = "collection";
	@ConfigurationParameter(name = PARAM_COLLECTION, defaultValue = "relationTypes")
	private String collection;

	/**
	 * The name of the field in Mongo that contains the relation type
	 *
	 * @baleen.config type
	 */
	public static final String PARAM_TYPE_FIELD = "typeField";
	@ConfigurationParameter(name = PARAM_TYPE_FIELD, defaultValue = "type")
	private String typeField;

	/**
	 * The name of the field in Mongo that contains the relation sub type
	 *
	 * @baleen.config type
	 */
	public static final String PARAM_SUBTYPE_FIELD = "subTypeField";
	@ConfigurationParameter(name = PARAM_SUBTYPE_FIELD, defaultValue = "subType")
	private String subTypeField;

	/**
	 * The name of the field in Mongo that contains the relation source type
	 *
	 * @baleen.config source
	 */
	public static final String PARAM_SOURCE_FIELD = "sourceField";
	@ConfigurationParameter(name = PARAM_SOURCE_FIELD, defaultValue = "source")
	private String sourceField;

	/**
	 * The name of the field in Mongo that contains the relation source type
	 *
	 * @baleen.config target
	 */
	public static final String PARAM_TARGET_FIELD = "targetField";
	@ConfigurationParameter(name = PARAM_TARGET_FIELD, defaultValue = "target")
	private String targetField;

	/**
	 * The name of the field in Mongo that contains the relation pos
	 *
	 * @baleen.config posField pos
	 */
	public static final String PARAM_POS_FIELD = "posField";
	@ConfigurationParameter(name = PARAM_POS_FIELD, defaultValue = "pos")
	private String posField;

	/**
	 * The name of the field in Mongo that contains the relation values
	 *
	 * @baleen.config posField pos
	 */
	public static final String PARAM_VALUES_FIELD = "valueField";
	@ConfigurationParameter(name = PARAM_VALUES_FIELD, defaultValue = "value")
	private String valuesField;

	/**
	 * The stemming algorithm to use, as defined in OpenNLP's SnowballStemmer.ALGORITHM enum
	 *
	 * @baleen.config ENGLISH
	 */
	public static final String PARAM_ALGORITHM = "algorithm";
	@ConfigurationParameter(name = PARAM_ALGORITHM, defaultValue = "ENGLISH")
	protected String algorithm;

	/**
	 * Should the words be stemmed before processing?
	 *
	 * Set false if you want a very precise match against your values, effectively they must be the
	 * interaction values. Set to true for a more relaxed match but which might produce false
	 * positives.
	 *
	 * @baleen.config true
	 */
	public static final String PARAM_STEM = "stem";
	@ConfigurationParameter(name = PARAM_STEM, defaultValue = "true")
	protected boolean stem;

	private final Multimap<String, InteractionTypeDefinition> definitions = HashMultimap.create();
	private SnowballStemmer stemmer;

	@Override
	public void doInitialize(final UimaContext aContext) throws ResourceInitializationException {
		super.doInitialize(aContext);

		
		ALGORITHM algo;
		try{
			algo = ALGORITHM.valueOf(algorithm);
		}catch(IllegalArgumentException iae){
			getMonitor().warn("Algorithm {} doesn't exist, defaulting to ENGLISH", algorithm, iae);
			algo = ALGORITHM.ENGLISH;
		}
		stemmer = new SnowballStemmer(algo);

		final MongoCollection<Document> dbCollection = mongo.getDB().getCollection(collection);

		for(Document o : dbCollection.find()){
			String type = (String) o.get(typeField);
			String subType = (String) o.get(subTypeField);
			String pos = (String) o.get(posField);
			List<?> values = (List<?>) o.get(valuesField);

			InteractionTypeDefinition definition = new InteractionTypeDefinition(type, subType, pos);

			values.stream()
					.filter(s -> s instanceof String)
					.forEach(s -> {
						String key = toKey(definition.getPos(), (String) s);
						definitions.put(key, definition);
					});
		}
	}

	private String toKey(String pos, String word) {
		CharSequence normalised = word.toLowerCase().trim();
		if (stem) {
			normalised = stemmer.stem(normalised);
		}
		return String.format("%s:%s", Character.toLowerCase(pos.charAt(0)), normalised);
	}

	@Override
	protected void doProcess(JCas jCas) throws AnalysisEngineProcessException {
		Map<Interaction, Collection<WordToken>> interactionToWords = JCasUtil.indexCovered(jCas, Interaction.class,
				WordToken.class);

		Collection<Interaction> allInteractions = new ArrayList<>(JCasUtil.select(jCas, Interaction.class));
		for (Interaction interaction : allInteractions) {
			String value = interaction.getCoveredText();
			Collection<WordToken> words = interactionToWords.get(interaction);

			if (words != null && !words.isEmpty() && value != null && !value.isEmpty()) {
				// So we have the covered words and the interaction value (ie the word covered by
				// the interact)

				// Look for a string match between the interaction value and the words then find all
				// the potential POS it could be

				Stream<String> keys = words.stream()
						.filter(p -> p.getCoveredText().equalsIgnoreCase(value))
						.map(w -> w.getPartOfSpeech())
						.distinct()
						.filter(Objects::nonNull)
						.map(p -> toKey(p, value));

				// For each interaction we create a new interaction which is has the right type info

				// This get does POS matching for us
				keys.map(definitions::get)
						.filter(l -> l != null && !l.isEmpty())
						.flatMap(Collection::stream)
						.forEach(d -> {
							Interaction i = ComparableEntitySpanUtils.copyInteraction(jCas, interaction.getBegin(),
									interaction.getEnd(), interaction);

							i.setRelationshipType(d.getType());
							i.setRelationSubType(d.getSubType());

							addToJCasIndex(i);
						});
			}
		}

		// Delete the old interaction, its either been replaced or not
		removeFromJCasIndex(allInteractions);
	}
	
	@Override
	public AnalysisEngineAction getAction() {
		return new AnalysisEngineAction(ImmutableSet.of(WordToken.class, Interaction.class), Collections.emptySet());
	}
}