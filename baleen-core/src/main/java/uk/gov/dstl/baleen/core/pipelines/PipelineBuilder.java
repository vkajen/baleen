//Dstl (c) Crown Copyright 2017
package uk.gov.dstl.baleen.core.pipelines;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.uima.UIMAException;
import org.apache.uima.UIMAFramework;
import org.apache.uima.analysis_component.AnalysisComponent;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.collection.CollectionReader;
import org.apache.uima.fit.descriptor.ExternalResource;
import org.apache.uima.fit.factory.AnalysisEngineFactory;
import org.apache.uima.fit.factory.CollectionReaderFactory;
import org.apache.uima.fit.factory.ExternalResourceFactory;
import org.apache.uima.fit.internal.ResourceManagerFactory;
import org.apache.uima.resource.ExternalResourceDescription;
import org.apache.uima.resource.Resource;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import uk.gov.dstl.baleen.core.history.BaleenHistory;
import uk.gov.dstl.baleen.core.history.logging.LoggingBaleenHistory;
import uk.gov.dstl.baleen.core.pipelines.orderers.IPipelineOrderer;
import uk.gov.dstl.baleen.core.pipelines.orderers.NoOpOrderer;
import uk.gov.dstl.baleen.core.utils.BaleenDefaults;
import uk.gov.dstl.baleen.core.utils.BuilderUtils;
import uk.gov.dstl.baleen.core.utils.YamlConfiguration;
import uk.gov.dstl.baleen.exceptions.BaleenException;
import uk.gov.dstl.baleen.exceptions.InvalidParameterException;
import uk.gov.dstl.baleen.exceptions.MissingParameterException;

/**
 * This class provides functionality to convert a Baleen YAML configuration file into a
 * {@link BaleenPipeline} that can be executed by Baleen.
 * <p>
 * The YAML configuration file should contain a single <i>collectionreader</i> 'object' and a list
 * of <i>annotators</i> and <i>consumers</i> objects. Each analysis engine should have a
 * <i>class</i> property, which refers to the class of the annotator. If the class cannot be found
 * as specified, then the default Baleen package for that type is searched instead (e.g.
 * uk.gov.dstl.baleen.annotators). If an collection reader, annotator or consumer has no properties
 * then the class property prefix is optional (i.e. the list item can consist solely of the
 * annotator class).
 * <p>
 * An `orderer` can also be specified to control the ordering of the pipeline. If not specified, the
 * default pipeline orderer will be used instead.
 * <p>
 * Any additional properties on the analysis engine are passed as Params to the analysis engine.
 * Additionally, any top level objects that aren't expected are assumed to be global parameters that
 * are passed to all analysis engines. Where locally specified parameters have the same name as
 * global ones, the local versions take precedent.
 * <p>
 * For example:
 *
 * <pre>
 * shape:
 *   color: red
 *   size: large
 *
 * history:
 *   class: uk.gov.dstl.baleen.core.history.memory.InMemoryBaleenHistory
 *   mergeDistinctEntities: true
 *   
 * orderer: NoOpPipelineOrderer
 *
 * collectionreader:
 *   class: DummyReader
 *   inputdirectory: \data\input
 *
 * annotators:
 *   - DummyAnnotator1
 *   - class: DummyAnnotatorWithParams
 *     min: 20
 *     max: 200
 *   - DummyAnnotator2
 *
 * consumers:
 *   - class: DummyConsumer
 *     shape.color: green
 * </pre>
 *
 * Here, the pipeline would run as follows with provided parameters listed in brackets:
 * <ul>
 * <li>DummyReader (shape.color: red, shape.size: large, inputdirectory: \data\input)</li>
 * <li>DummyAnnotator1 (shape.color: red, shape.size: large)</li>
 * <li>DummyAnnotatorWithParams (shape.color: red, shape.size: large, min: 20, max: 20)</li>
 * <li>DummyAnnotator2 (shape.color: red, shape.size: large)</li>
 * <li>DummyConsumer (shape.color: green, shape.size: large)</li>
 * </ul>
 * <p>
 * Resources are automatically detected (assuming the analysis engine has used the @ExternalResource
 * annotation) and created. Resources should use global parameters (e.g. shape.color in the above
 * example) to initialise themselves, as these are the only ones that will be passed to them.
 * <p>
 * If not otherwise specified, <em>history.class</em> will default to <code>uk.gov.dstl.baleen.core.history.logging.LoggingBaleenHistory</code>,
 * and <em>orderer</em> will default to <code>uk.gov.dstl.baleen.core.pipelines.orderers.DependencyGraphPipelineOrderer</code>.
 *
 * @baleen.javadoc
 */
public class PipelineBuilder {
	/**
	 * Key for the configuration parameter holding the pipeline name
	 */
	public static final String PIPELINE_NAME = "__pipelineName";
	/**
	 * Key for the configuration parameter holding the annotator UUID
	 */
	public static final String ANNOTATOR_UUID = "__uuid";
	/**
	 * Key for the resource holding the history object
	 */
	public static final String BALEEN_HISTORY = "__baleenHistory";
	/**
	 * Metadata key for storing the original YAML configuration
	 */
	public static final String ORIGINAL_CONFIG = "__originalConfig";

	private static final List<String> ignoreParams = new ArrayList<>(Arrays.asList("class"));

	private static final Logger LOGGER = LoggerFactory.getLogger(PipelineBuilder.class);

	protected final String name;
	protected final String yaml;
	
	protected Map<String, Object> globalConfig;
	protected Map<String, Object> collectionReaderConfig;
	protected List<Object> annotatorsConfig;
	protected List<Object> consumersConfig;
	protected String pipelineOrderer;
	
	private ResourceManager resourceManager;
	private Map<String, ExternalResourceDescription> resourceDescriptors;
	
	/**
	 * Construct a PipelineBuilder from the name and YAML
	 * 
	 * @param name
	 * 		Pipeline name
	 * @param yaml
	 * 		Pipeline YAML
	 */
	public PipelineBuilder(String name, String yaml){
		this.name = name;
		this.yaml = yaml;
	}
	
	/**
	 * Create a new BaleenOrderingPipeline from the name and
	 * YAML configuration provided to the constructor
	 */
	public BaleenPipeline createNewPipeline() throws BaleenException{
		LOGGER.info("Creating pipeline {}", name);
		
		//Read in configuration from YAML
		readConfiguration();
		
		//Initialise resource manager
		resourceDescriptors = new HashMap<>();
		
		try{
			resourceManager = ResourceManagerFactory.newResourceManager();
		}catch(UIMAException ue){
			throw new BaleenException("Could not create Resource Manager", ue);
		}
		
		//Create components
		LOGGER.debug("Configuring pipeline orderer");
		IPipelineOrderer orderer = createPipelineOrderer();
		
		LOGGER.debug("Configuring history");
		ExternalResourceDescription erdHistory = configureHistory();
		resourceDescriptors.put(BALEEN_HISTORY, erdHistory);
		
		LOGGER.debug("Creating collection reader");
		CollectionReader collectionReader = createCollectionReader();
		
		List<AnalysisEngine> annotators;
		if(annotatorsConfig != null && !annotatorsConfig.isEmpty()){
			LOGGER.debug("Creating annotators");
			annotators = createAnnotators();
		}else{
			annotators = Collections.emptyList();
		}
		
		List<AnalysisEngine> consumers;
		if(consumersConfig != null && !consumersConfig.isEmpty()){
			LOGGER.debug("Creating consumers");
			consumers = createConsumers();
		}else{
			consumers = Collections.emptyList();
		}
		
		return toPipeline(name, yaml, orderer, collectionReader, annotators, consumers);
	}
	
	/**
	 * Take a number of parameters and return a pipeline (or sub-class)
	 * 
	 * @param name
	 * 		Pipeline name
	 * @param yaml
	 * 		Original YAML
	 * @param orderer
	 * 		Pipeline orderer to use
	 * @param collectionReader
	 * 		Collection reader to use
	 * @param annotators
	 * 		List of annotators (can be empty)
	 * @param consumers
	 * 		List of consumers (can be empty)
	 * 
	 * @return
	 * 		Configured BaleenPipeline
	 */
	protected BaleenPipeline toPipeline(String name, String yaml, IPipelineOrderer orderer, CollectionReader collectionReader, List<AnalysisEngine> annotators, List<AnalysisEngine> consumers){
		return new BaleenPipeline(name, yaml, orderer, collectionReader, annotators, consumers);
	}
	
	/**
	 * Read configuration into the class variables
	 */
	@SuppressWarnings("unchecked")
	protected void readConfiguration(){
		LOGGER.debug("Reading configuration");
		Yaml y = new Yaml();
		String cleanYaml = YamlConfiguration.cleanTabs(yaml);
		globalConfig = (Map<String, Object>) y.load(cleanYaml);
		
		pipelineOrderer = BuilderUtils.getClassNameFromConfig(globalConfig.remove("orderer"));
		
		Object s = globalConfig.remove("collectionreader");
		if(s instanceof String){
			collectionReaderConfig = new HashMap<>();
			collectionReaderConfig.put("class", s);
		}else{
			collectionReaderConfig = (Map<String, Object>) s;
		}
		
		annotatorsConfig = (List<Object>) globalConfig.remove("annotators");
		consumersConfig = (List<Object>) globalConfig.remove("consumers");

		globalConfig = BuilderUtils.flattenConfig(null, globalConfig);
		globalConfig.put(PIPELINE_NAME, name);
	}
	
	/**
	 * Create a new pipeline orderer
	 */
	private IPipelineOrderer createPipelineOrderer(){
		if(pipelineOrderer == null){
			LOGGER.info("Pipeline orderer not specified - default will be used");
			return getDefaultPipelineOrderer();
		}
		
		Class<?> c;
		try{
			c = BuilderUtils.getClassFromString(pipelineOrderer, getDefaultOrdererPackage());
		}catch(InvalidParameterException ipe){
			LOGGER.warn("Couldn't find specified orderer - default will be used", ipe);
			return getDefaultPipelineOrderer();
		}
		
		if(!(IPipelineOrderer.class.isAssignableFrom(c))){
			LOGGER.warn("Specified orderer does not implement IPipelineOrderer interface - default will be used");
			return getDefaultPipelineOrderer();
		}
		
		try {
			return (IPipelineOrderer) c.newInstance();
		} catch (Exception e) {
			LOGGER.error("Specified orderer does not implement IPipelineOrderer interface - default will be used", e);
			return getDefaultPipelineOrderer();
		}
	}
	
	private IPipelineOrderer getDefaultPipelineOrderer(){
		try{
			return (IPipelineOrderer) Class.forName(BaleenDefaults.DEFAULT_ORDERER).newInstance();
		}catch(Exception e){
			LOGGER.error("Unable to create default pipeline orderer, pipeline will run in order specified in configuration", e);
			return new NoOpOrderer();
		}
	}
	
	/**
	 * Configure a new history resource object
	 */
	@SuppressWarnings("unchecked")
	private ExternalResourceDescription configureHistory() {
		String historyClass = (String) globalConfig.get("history.class");

		Class<? extends BaleenHistory> clazz = null;

		if (historyClass != null) {
			try {
				clazz = (Class<? extends BaleenHistory>) Class.forName(historyClass);
			} catch (ClassNotFoundException | ClassCastException e) {
				LOGGER.warn("Unable to find perferred history implementation {}", historyClass, e);
			}
		} else {
			LOGGER.warn("No history implementation specified");
		}

		if (clazz == null) {
			clazz = LoggingBaleenHistory.class;
			LOGGER.info("Using the default history implementation {}", clazz.getCanonicalName());
		}

		Object[] params = BuilderUtils.extractParams(globalConfig, ignoreParams, getOrCreateResources(clazz));
		
		Object[] stringParams = BuilderUtils.convertToStringArray(params);
		
		return ExternalResourceFactory.createExternalResourceDescription(BALEEN_HISTORY, clazz, stringParams);
	}
	
	/**
	 * Create a new Collection Reader
	 */
	private CollectionReader createCollectionReader() throws BaleenException{
		String className = BuilderUtils.getClassNameFromConfig(collectionReaderConfig);
		Map<String, Object> params = BuilderUtils.flattenConfig(null, BuilderUtils.getParamsFromConfig(collectionReaderConfig));

		if (className == null || className.isEmpty()) {
			throw new InvalidParameterException("Collection Reader class not specified");
		}

		Map<String, Object> nonNullParams = params;
		if (nonNullParams == null) {
			nonNullParams = Collections.emptyMap();
		}

		try {
			Class<? extends CollectionReader> clazz = BuilderUtils.getClassFromString(className, getDefaultReaderPackage());
			Map<String, ExternalResourceDescription> crResources = getOrCreateResources(clazz);
			Object[] paramArr = BuilderUtils.mergeAndExtractParams(globalConfig, nonNullParams, ignoreParams, crResources);

			return UIMAFramework.produceCollectionReader(CollectionReaderFactory.createReaderDescription(clazz, paramArr), resourceManager, null);
		} catch (ResourceInitializationException e) {
			throw new BaleenException("Couldn't initialize collection reader", e);
		}
	}
	
	/**
	 * Create a new analysis engine
	 */
	private AnalysisEngine createAnalysisEngine(String className, String defaultPackage, Map<String, Object> annotatorConfig, Object originalConfig) throws BaleenException{
		if (className == null || className.isEmpty()) {
			throw new MissingParameterException("No class name provided for annotator, or unable to parse list item - analysis engine will be skipped");
		}

		try {
			Class<? extends AnalysisComponent> clazz = BuilderUtils.getClassFromString(className, defaultPackage);
			Map<String, ExternalResourceDescription> aResources = getOrCreateResources(clazz);
			
			annotatorConfig.put(ANNOTATOR_UUID, UUID.randomUUID().toString());
			
			Object[] aParams = BuilderUtils.mergeAndExtractParams(globalConfig, annotatorConfig, ignoreParams, aResources);

			AnalysisEngine ae = createEngine(clazz, resourceManager, aParams);
			ae.setConfigParameterValue(ORIGINAL_CONFIG, originalConfig);
			
			return ae;
		} catch (BaleenException | ResourceInitializationException e) {
			throw new BaleenException("Failed to build annotator description - analysis engine will be skipped", e);
		}
	}
	
	/**
	 * Create new annotators
	 */
	private List<AnalysisEngine> createAnnotators() throws BaleenException {
		List<AnalysisEngine> analysisEngines = new ArrayList<>();
		
		for (Object objAnnotator : annotatorsConfig) {
			String className = BuilderUtils.getClassNameFromConfig(objAnnotator);
			Map<String, Object> params = BuilderUtils.flattenConfig(null, BuilderUtils.getParamsFromConfig(objAnnotator));
			
			try{
				analysisEngines.add(createAnalysisEngine(className, getDefaultAnnotatorPackage(), params, objAnnotator));
			}catch(BaleenException be){
				LOGGER.error("Annotator {} could not be created and has been skipped", className, be);
			}
		}

		return analysisEngines;
	}
	
	/**
	 * Create new consumers
	 */
	private List<AnalysisEngine> createConsumers() throws BaleenException {
		List<AnalysisEngine> analysisEngines = new ArrayList<>();
		
		for (Object objConsumer : consumersConfig) {
			String className = BuilderUtils.getClassNameFromConfig(objConsumer);
			Map<String, Object> params = BuilderUtils.flattenConfig(null, BuilderUtils.getParamsFromConfig(objConsumer));

			try{
				analysisEngines.add(createAnalysisEngine(className, getDefaultConsumerPackage(), params, objConsumer));
			}catch(BaleenException be){
				LOGGER.error("Consumer {} could not be created and has been skipped", className, be);
			}
		}

		return analysisEngines;
	}
	
	/**
	 * Get a resource if it already exists, otherwise create a new one
	 */
	@SuppressWarnings("unchecked")
	private Map<String, ExternalResourceDescription> getOrCreateResources(Class<?> clazz) {
		Map<String, ExternalResourceDescription> ret = new HashMap<>();

		List<Field> fields = new ArrayList<>();

		Class<?> c = clazz;
		while (c != null && c != Object.class) {
			fields.addAll(Arrays.asList(c.getDeclaredFields()));
			c = c.getSuperclass();
		}

		for (Field f : fields) {
			if (f.isAnnotationPresent(ExternalResource.class) && Resource.class.isAssignableFrom(f.getType())) {
				ExternalResource annotation = f.getAnnotation(ExternalResource.class);
				String key = annotation.key();

				ExternalResourceDescription erd;
				if (resourceDescriptors.containsKey(key)) {
					erd = resourceDescriptors.get(key);
				} else {
					Map<String, ExternalResourceDescription> erds = getOrCreateResources(f.getType());
					Object[] params = BuilderUtils.extractParams(globalConfig, ignoreParams, erds);
					// Since createExternalResourceDescription actually casts Objects to Strings we need to convert
					Object[] stringParams = BuilderUtils.convertToStringArray(params);
					erd = ExternalResourceFactory.createExternalResourceDescription(key, (Class<? extends Resource>) f.getType(), stringParams);
					resourceDescriptors.put(key, erd);
				}

				ret.put(key, erd);
			}
		}

		return ret;
	}
	
	/**
	 * Create a new analysis engine
	 */
	private AnalysisEngine createEngine(Class<? extends AnalysisComponent> componentClass, ResourceManager resourceManager, Object... configurationData) throws ResourceInitializationException{
		return UIMAFramework.produceAnalysisEngine(AnalysisEngineFactory.createEngineDescription(componentClass, configurationData), resourceManager, null);
	}
	
	/**
	 * Return the package to use as the default location for Orderers.
	 * 
	 * This is done as a method rather than accessing the constant
	 * directly so that sub-classes can override it
	 */
	protected String getDefaultOrdererPackage(){
		return BaleenDefaults.DEFAULT_ORDERER_PACKAGE;
	}
	
	/**
	 * Return the package to use as the default location for Collection Readers.
	 * 
	 * This is done as a method rather than accessing the constant
	 * directly so that sub-classes can override it
	 */
	protected String getDefaultReaderPackage(){
		return BaleenDefaults.DEFAULT_READER_PACKAGE;
	}
	
	/**
	 * Return the package to use as the default location for Annotators.
	 * 
	 * This is done as a method rather than accessing the constant
	 * directly so that sub-classes can override it
	 */
	protected String getDefaultAnnotatorPackage(){
		return BaleenDefaults.DEFAULT_ANNOTATOR_PACKAGE;
	}
	
	/**
	 * Return the package to use as the default location for Consumers.
	 * 
	 * This is done as a method rather than accessing the constant
	 * directly so that sub-classes can override it
	 */
	protected String getDefaultConsumerPackage(){
		return BaleenDefaults.DEFAULT_CONSUMERS_PACKAGE;
	}
}
