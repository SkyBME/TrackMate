package fiji.plugin.trackmate;

import static fiji.plugin.trackmate.detection.DetectorKeys.KEY_TARGET_CHANNEL;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.imglib2.algorithm.MultiThreaded;
import net.imglib2.multithreading.SimpleMultiThreading;

import org.jgrapht.graph.DefaultWeightedEdge;

import fiji.plugin.trackmate.features.edges.EdgeFeatureAnalyzer;
import fiji.plugin.trackmate.features.spot.SpotFeatureAnalyzer;
import fiji.plugin.trackmate.features.spot.SpotFeatureAnalyzerFactory;
import fiji.plugin.trackmate.features.track.TrackFeatureAnalyzer;
import fiji.plugin.trackmate.util.TMUtils;

/**
 * This class represents the part of the {@link TrackMateModel} that is in charge 
 * of dealing with spot features and track features.
 * @author Jean-Yves Tinevez, 2011, 2012
 *
 */
public class FeatureModel implements MultiThreaded {

//	private static final boolean DEBUG = true;
	
	/*
	 * FIELDS
	 */

	/** The list of spot features that are available for the spots of this model. */
	private List<String> spotFeatures;
	/** The map of the spot feature names. */
	private Map<String, String> spotFeatureNames;
	/** The map of the spot feature abbreviated names. */
	private Map<String, String> spotFeatureShortNames;
	/** The map of the spot feature dimension. */
	private Map<String, Dimension> spotFeatureDimensions;

	private ArrayList<String> trackFeatures = new ArrayList<String>();
	private HashMap<String, String> trackFeatureNames = new HashMap<String, String>();
	private HashMap<String, String> trackFeatureShortNames = new HashMap<String, String>();
	private HashMap<String, Dimension> trackFeatureDimensions = new HashMap<String, Dimension>();
	/**
	 * Feature storage. We use a Map of Map as a 2D Map. The list maps each
	 * feature to its track map. The track map maps each
	 * track ID to the double value for the specified feature.
	 */
	protected Map<String, Map<Integer, Double>> trackFeatureValues =  new ConcurrentHashMap<String, Map<Integer, Double>>();

	/**
	 * Feature storage for edges.
	 */
	protected ConcurrentHashMap<DefaultWeightedEdge, ConcurrentHashMap<String, Object>> edgeFeatureValues 
	= new ConcurrentHashMap<DefaultWeightedEdge, ConcurrentHashMap<String, Object>>();

	private ArrayList<String> edgeFeatures = new ArrayList<String>();
	private HashMap<String, String> edgeFeatureNames = new HashMap<String, String>();
	private HashMap<String, String> edgeFeatureShortNames = new HashMap<String, String>();
	private HashMap<String, Dimension> edgeFeatureDimensions = new HashMap<String, Dimension>();

	protected SpotFeatureAnalyzerProvider spotAnalyzerProvider;
	protected TrackFeatureAnalyzerProvider trackAnalyzerProvider;
	private EdgeFeatureAnalyzerProvider edgeFeatureAnalyzerProvider;

	private TrackMateModel model;
	protected int numThreads;


	/*
	 * CONSTRUCTOR
	 */

	public FeatureModel(TrackMateModel model) {
		this.model = model;
		setNumThreads();
		// To initialize the spot features with the basic features:
		setSpotFeatureFactory(null);
	}


	/*
	 * METHODS
	 */

	/*
	 * SPOT FEATURES
	 */


	/**
	 * Calculate given features for the all detected spots of this model,
	 * according to the {@link Settings} set in the model.
	 * <p>
	 * Features are calculated for each spot, using their location, and the raw
	 * image. Since a {@link SpotFeatureAnalyzer} can compute more than a feature
	 * at once, spots might received more data than required.
	 */
	public void computeSpotFeatures(final List<String> features) {
		computeSpotFeatures(model.getSpots(), features);
	}

	/**
	 * Calculate given features for the all filtered spots of this model,
	 * according to the {@link Settings} set in this model.
	 */
	public void computeSpotFeatures(final String feature) {
		ArrayList<String> features = new ArrayList<String>(1);
		features.add(feature);
		computeSpotFeatures(features);
	}

	/**
	 * Calculate given features for the given spots, according to the
	 * {@link Settings} set in this model.
	 * <p>
	 * Features are calculated for each spot, using their location, and the raw
	 * images. Since a {@link SpotFeatureAnalyzer} can compute more than a feature
	 * at once, spots might received more data than required.
	 */
	public void computeSpotFeatures(final SpotCollection toCompute, final List<String> features) {
		final HashSet<String> selectedKeys = new HashSet<String>(); // We want to keep ordering
		for(String feature : features) {
			for(String analyzer : spotAnalyzerProvider.getAvailableSpotFeatureAnalyzers()) {
				if (spotAnalyzerProvider.getFeatures(analyzer).contains(feature)) {
					selectedKeys.add(analyzer);
				}
			}
		}
		List<SpotFeatureAnalyzerFactory<?>> selectedAnalyzers = new ArrayList<SpotFeatureAnalyzerFactory<?>>();
		for(String key : selectedKeys) {
			selectedAnalyzers.add(spotAnalyzerProvider.getSpotFeatureAnalyzer(key));
		}
		computeSpotFeaturesAgent(toCompute, selectedAnalyzers );
	}

	/**
	 * Calculate all features for the given spot collection. Does nothing
	 * if the {@link #spotAnalyzerProvider} was not set, which is typically the 
	 * case when the iniModules() method of the plugin has not been called. 
	 * <p>
	 * Features are calculated for each spot, using their location, and the raw
	 * image. 
	 */
	public void computeSpotFeatures(final SpotCollection toCompute) {
		if (null == spotAnalyzerProvider)
			return;
		List<String> analyzerNames = spotAnalyzerProvider.getAvailableSpotFeatureAnalyzers();
		List<SpotFeatureAnalyzerFactory<?>> spotFeatureAnalyzers = new ArrayList<SpotFeatureAnalyzerFactory<?>>(analyzerNames.size());
		for (String analyzerName : analyzerNames) {
			spotFeatureAnalyzers.add(spotAnalyzerProvider.getSpotFeatureAnalyzer(analyzerName));
		}
		computeSpotFeaturesAgent(toCompute, spotFeatureAnalyzers);
	}


	/** 
	 * Set the list of spot feature analyzers that will be used to compute spot features.
	 * Setting this field will automatically sets the derived fields: {@link #spotFeatures},
	 * {@link #spotFeatureNames}, {@link #spotFeatureShortNames} and {@link #spotFeatureDimensions}.
	 * These fields will be generated from the {@link SpotFeatureAnalyzer} content, returned 
	 * by its methods {@link SpotFeatureAnalyzer#getFeatures()}, etc... and will be added
	 * in the order given by the list.
	 * 
	 * @see #updateFeatures(List) 
	 * @see #updateFeatures(Spot)
	 */
	public void setSpotFeatureFactory(final SpotFeatureAnalyzerProvider spotAnalyzerProvider) {
		this.spotAnalyzerProvider = spotAnalyzerProvider;

		spotFeatures = new ArrayList<String>();
		ArrayList<String> fromAnalyzersFeatures = new ArrayList<String>();
		// Add the basic features
		spotFeatures.addAll(Spot.FEATURES);
		// Features from analyzers
		if (null != spotAnalyzerProvider) {
			List<String> analyzers = spotAnalyzerProvider.getAvailableSpotFeatureAnalyzers();
			for(String analyzer : analyzers) {
				spotFeatures.addAll(spotAnalyzerProvider.getFeatures(analyzer));
				fromAnalyzersFeatures.addAll(spotAnalyzerProvider.getFeatures(analyzer));
			}
		}

		spotFeatureNames = new HashMap<String, String>();
		spotFeatureShortNames = new HashMap<String, String>();
		spotFeatureDimensions = new HashMap<String, Dimension>();
		// Add the basic features
		spotFeatureNames.putAll(Spot.FEATURE_NAMES);
		spotFeatureShortNames.putAll(Spot.FEATURE_SHORT_NAMES);
		spotFeatureDimensions.putAll(Spot.FEATURE_DIMENSIONS);
		// Features from analyzers
		if (null != spotAnalyzerProvider) {
			for(String feature : fromAnalyzersFeatures) {
				spotFeatureNames.put(feature, spotAnalyzerProvider.getFeatureName(feature));
				spotFeatureShortNames.put(feature, spotAnalyzerProvider.getFeatureShortName(feature));
				spotFeatureDimensions.put(feature, spotAnalyzerProvider.getFeatureDimension(feature));
			}
		}
	}

	/**
	 * Return the list of the spot features that are dealt with in this model.
	 */
	public List<String> getSpotFeatures() {
		return spotFeatures;
	}

	/**
	 * Return the name mapping of the spot features that are dealt with in this model.
	 * @return
	 */
	public Map<String, String> getSpotFeatureNames() {
		return spotFeatureNames;
	}

	/**
	 * Return the short name mapping of the spot features that are dealt with in this model.
	 * @return
	 */
	public Map<String, String> getSpotFeatureShortNames() {
		return spotFeatureShortNames;
	}

	/**
	 * Return the dimension mapping of the spot features that are dealt with in this model.
	 * @return
	 */
	public Map<String, Dimension> getSpotFeatureDimensions() {
		return spotFeatureDimensions;
	}

	/** 
	 * Set the list of track feature analyzers that will be used to compute track features.
	 * Setting this field will automatically sets the derived fields: {@link #trackFeatures},
	 * {@link #trackFeatureNames}, {@link #trackFeatureShortNames} and {@link #trackFeatureDimensions}.
	 * These fields will be generated from the {@link TrackFeatureAnalyzer} content, returned 
	 * by its methods {@link TrackFeatureAnalyzer#getFeatures()}, etc... and will be added
	 * in the order given by the list.
	 * 
	 * @see #computeTrackFeatures()
	 */
	public void setTrackFeatureProvider(TrackFeatureAnalyzerProvider trackAnalyzerProvider) {
		this.trackAnalyzerProvider = trackAnalyzerProvider;
		// Collect all the track feature we will have to deal with
		trackFeatures = new ArrayList<String>();
		for (String analyzer : trackAnalyzerProvider.getAvailableTrackFeatureAnalyzers()) {
			trackFeatures.addAll(trackAnalyzerProvider.getFeaturesForKey(analyzer));
		}
		// Collect track feature metadata
		trackFeatureNames = new HashMap<String, String>();
		trackFeatureShortNames = new HashMap<String, String>();
		trackFeatureDimensions = new HashMap<String, Dimension>();
		for (String trackFeature : trackFeatures) {
			trackFeatureNames.put(trackFeature, trackAnalyzerProvider.getFeatureName(trackFeature));
			trackFeatureShortNames.put(trackFeature, trackAnalyzerProvider.getFeatureShortName(trackFeature));
			trackFeatureDimensions.put(trackFeature, trackAnalyzerProvider.getFeatureDimension(trackFeature));
		}
		// Instantiate track feature value map, once for all
		this.trackFeatureValues = new ConcurrentHashMap<String, Map<Integer, Double>>(trackFeatures.size());
	}

	public void setEdgeFeatureProvider(final EdgeFeatureAnalyzerProvider edgeFeatureAnalyzerProvider) {
		this.edgeFeatureAnalyzerProvider = edgeFeatureAnalyzerProvider;

		edgeFeatures = new ArrayList<String>();
		for (String analyzer : edgeFeatureAnalyzerProvider.getAvailableEdgeFeatureAnalyzers()) {
			edgeFeatures.addAll(edgeFeatureAnalyzerProvider.getFeatures(analyzer));
		}

		edgeFeatureNames = new HashMap<String, String>();
		edgeFeatureShortNames = new HashMap<String, String>();
		edgeFeatureDimensions = new HashMap<String, Dimension>();
		for (String edgeFeature : edgeFeatures) {
			edgeFeatureNames.put(edgeFeature, edgeFeatureAnalyzerProvider.getFeatureName(edgeFeature));
			edgeFeatureShortNames.put(edgeFeature, edgeFeatureAnalyzerProvider.getFeatureShortName(edgeFeature));
			edgeFeatureDimensions.put(edgeFeature, edgeFeatureAnalyzerProvider.getFeatureDimension(edgeFeature));
		}
	}


	/**
	 * Return a map of feature values for the spot collection held
	 * by this instance. Each feature maps a double array, with 1 element per
	 * {@link Spot}, all pooled together.
	 */
	public Map<String, double[]> getSpotFeatureValues() {
		return TMUtils.getSpotFeatureValues(model.getSpots(), spotFeatures, model.getLogger());
	}

	/**
	 * The method in charge of computing spot features with the given {@link SpotFeatureAnalyzer}s, for the
	 * given {@link SpotCollection}.
	 * @param toCompute
	 * @param analyzers
	 */
	private void computeSpotFeaturesAgent(final SpotCollection toCompute, final List<SpotFeatureAnalyzerFactory<?>> analyzerFactories) {

		final Settings settings = model.getSettings();
		final Logger logger = model.getLogger();

		// Can't compute any spot feature without an image to compute on.
		if (settings.imp == null)
			return;

		final List<Integer> frameSet = new ArrayList<Integer>(toCompute.keySet());
		final int numFrames = frameSet.size();

		final AtomicInteger ai = new AtomicInteger(0);
		final AtomicInteger progress = new AtomicInteger(0);
		final Thread[] threads = SimpleMultiThreading.newThreads(numThreads);

		int tc = 0;
		if (settings != null && settings.detectorSettings != null) {
			// Try to extract it from detector settings target channel
			Map<String, Object> ds = settings.detectorSettings;
			Object obj = ds.get(KEY_TARGET_CHANNEL);
			if (null != obj && obj instanceof Integer) {
				tc = ((Integer) obj) - 1;
			}
		}
		final int targetChannel = tc;

		// Prepare the thread array
		for (int ithread = 0; ithread < threads.length; ithread++) {

			threads[ithread] = new Thread("TrackMate spot feature calculating thread " + (1 + ithread) + "/" + threads.length) {

				public void run() {

					for (int index = ai.getAndIncrement(); index < numFrames; index = ai.getAndIncrement()) {

						int frame = frameSet.get(index);
						for (SpotFeatureAnalyzerFactory<?> factory : analyzerFactories) {
							factory.getAnalyzer(frame, targetChannel).process();
						}

						logger.setProgress(progress.incrementAndGet() / (float) numFrames);
					} // Finished looping over frames
				}
			};
		}
		logger.setStatus("Calculating features...");
		logger.setProgress(0);

		SimpleMultiThreading.startAndJoin(threads);

		logger.setProgress(1);
		logger.setStatus("");
	}


	/*
	 * EDGE FEATURES
	 */

	public void putEdgeFeature(DefaultWeightedEdge edge, final String featureName, final Double featureValue) {
		ConcurrentHashMap<String, Object> map = edgeFeatureValues.get(edge);
		if (null == map) {
			map = new ConcurrentHashMap<String, Object>();
			edgeFeatureValues.put(edge, map);
		}
		map.put(featureName, featureValue);
	}

	public Object getEdgeFeature(DefaultWeightedEdge edge, final String featureName) {
		ConcurrentHashMap<String, Object> map = edgeFeatureValues.get(edge);
		if (null == map) {
			return null;
		}
		return map.get(featureName);
	}

	public List<String> getEdgeFeatures() {
		return edgeFeatures;
	}

	/**
	 * Return the name mapping of the edge features that are dealt with in this model.
	 * @return
	 */
	public Map<String, String> getEdgeFeatureNames() {
		return edgeFeatureNames;
	}

	/**
	 * Return the short name mapping of the edge features that are dealt with in this model.
	 * @return
	 */
	public Map<String, String> getEdgeFeatureShortNames() {
		return edgeFeatureShortNames;
	}

	/**
	 * Return the dimension mapping of the edge features that are dealt with in this model.
	 * @return
	 */
	public Map<String, Dimension> getEdgeFeatureDimensions() {
		return edgeFeatureDimensions;
	}


	/*
	 * TRACK FEATURES
	 */



	/**
	 * Return the list of the track features that are dealt with in this model.
	 */
	public List<String> getTrackFeatures() {
		return trackFeatures;
	}

	/**
	 * Return the name mapping of the track features that are dealt with in this model.
	 * @return
	 */
	public Map<String, String> getTrackFeatureNames() {
		return trackFeatureNames;
	}

	/**
	 * Return the short name mapping of the track features that are dealt with in this model.
	 * @return
	 */
	public Map<String, String> getTrackFeatureShortNames() {
		return trackFeatureShortNames;
	}

	/**
	 * Return the dimension mapping of the track features that are dealt with in this model.
	 * @return
	 */
	public Map<String, Dimension> getTrackFeatureDimensions() {
		return trackFeatureDimensions;
	}

	public synchronized void putTrackFeature(final Integer trackID, final String feature, final Double value) {
		Map<Integer, Double> valueMap = trackFeatureValues.get(feature);
		if (null == valueMap) {
			valueMap = new HashMap<Integer, Double>(model.getTrackModel().getNFilteredTracks());
			trackFeatureValues.put(feature, valueMap);
		}
		valueMap.put(trackID, value);
	}

	/**
	 * @return the numerical value of the specified track feature for the specified track.
	 * @param trackID the track ID to quest.
	 * @param feature the desired feature.
	 */
	public Double getTrackFeature(final Integer trackID, final String feature) {
		Map<Integer, Double> valueMap = trackFeatureValues.get(feature);
		return valueMap.get(trackID);
	}

	public Map<String, double[]> getTrackFeatureValues() {
		final Map<String, double[]> featureValues = new HashMap<String, double[]>();
		Double val;
		int nTracks = model.getTrackModel().getNTracks();
		for (String feature : trackFeatures) {
			// Make a double array to comply to JFreeChart histograms
			boolean noDataFlag = true;
			final double[] values = new double[nTracks];
			int index = 0;
			for (Integer trackID : model.getTrackModel().getTrackIDs()) {
				val = getTrackFeature(trackID, feature);
				if (null == val)
					continue;
				values[index++] = val;
				noDataFlag = false;
			}

			if (noDataFlag)
				featureValues.put(feature, null);
			else
				featureValues.put(feature, values);
		}
		return featureValues;
	}

	/**
	 * Calculate all features for the tracks with the given IDs.
	 */
	public void computeTrackFeatures(final Collection<Integer> trackIDs, boolean doLogIt) {
		final Logger logger = model.getLogger();
		if (doLogIt) {
			logger.log("Computing track features:\n");		
		}
		// Reset track feature value map
		trackFeatureValues.clear();
		/*
		 *  Compute new track feature. Analyzers will use the #putFeature method to store results,
		 *  which will regenerate the value map.
		 */
		for (String analyzerKey : trackAnalyzerProvider.getAvailableTrackFeatureAnalyzers()) {
			// Compute features
			TrackFeatureAnalyzer analyzer = trackAnalyzerProvider.getTrackFeatureAnalyzer(analyzerKey);
			analyzer.process(trackIDs);
			if (doLogIt)
				logger.log("  - " + analyzer.toString() + " in " + analyzer.getProcessingTime() + " ms.\n");
		}
	}

	public void computeEdgeFeatures(final Collection<DefaultWeightedEdge> edges, boolean doLogIt) {
		final Logger logger = model.getLogger();
		if (doLogIt) {
			logger.log("Computing edge features:\n");		
		}
		for(String key : edgeFeatureAnalyzerProvider.getAvailableEdgeFeatureAnalyzers()) {
			EdgeFeatureAnalyzer analyzer = edgeFeatureAnalyzerProvider.getEdgeFeatureAnalyzer(key);
			analyzer.process(edges);
			if (doLogIt)
				logger.log("  - " + analyzer.toString() + " in " + analyzer.getProcessingTime() + " ms.\n");
		}
	}

	@Override
	public void setNumThreads() {
		this.numThreads = Runtime.getRuntime().availableProcessors();
	}


	@Override
	public void setNumThreads(int numThreads) {
		this.numThreads = numThreads;
	}


	@Override
	public int getNumThreads() {
		return numThreads;
	}
}