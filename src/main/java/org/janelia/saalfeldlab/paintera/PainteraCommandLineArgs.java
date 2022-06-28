package org.janelia.saalfeldlab.paintera;

import ch.qos.logback.classic.Level;
import com.google.gson.JsonObject;
import com.pivovarit.function.ThrowingConsumer;
import com.pivovarit.function.ThrowingFunction;
import net.imglib2.Dimensions;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.algorithm.util.Grids;
import net.imglib2.converter.ARGBCompositeColorConverter;
import net.imglib2.img.cell.AbstractCellImg;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.volatiles.AbstractVolatileRealType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.labels.Label;
import org.janelia.saalfeldlab.labels.blocks.LabelBlockLookup;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5TreeNode;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.paintera.composition.ARGBCompositeAlphaAdd;
import org.janelia.saalfeldlab.paintera.data.DataSource;
import org.janelia.saalfeldlab.paintera.data.n5.DataTypeNotSupported;
import org.janelia.saalfeldlab.paintera.data.n5.N5ChannelDataSourceMetadata;
import org.janelia.saalfeldlab.paintera.id.IdService;
import org.janelia.saalfeldlab.paintera.id.N5IdService;
import org.janelia.saalfeldlab.paintera.state.ChannelSourceState;
import org.janelia.saalfeldlab.paintera.state.SourceState;
import org.janelia.saalfeldlab.paintera.state.label.ConnectomicsLabelState;
import org.janelia.saalfeldlab.paintera.state.label.n5.N5Backend;
import org.janelia.saalfeldlab.paintera.state.metadata.MetadataState;
import org.janelia.saalfeldlab.paintera.state.metadata.MetadataUtils;
import org.janelia.saalfeldlab.paintera.state.metadata.N5ContainerState;
import org.janelia.saalfeldlab.paintera.state.raw.ConnectomicsRawState;
import org.janelia.saalfeldlab.paintera.state.raw.n5.N5BackendRaw;
import org.janelia.saalfeldlab.paintera.ui.PainteraAlerts;
import org.janelia.saalfeldlab.paintera.util.logging.LogUtils;
import org.janelia.saalfeldlab.util.NamedThreadFactory;
import org.janelia.saalfeldlab.util.grids.LabelBlockLookupAllBlocks;
import org.janelia.saalfeldlab.util.grids.LabelBlockLookupNoBlocks;
import org.janelia.saalfeldlab.util.n5.N5Helpers;
import org.janelia.saalfeldlab.util.n5.N5Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(name = "Paintera", showDefaultValues = true, resourceBundle = "org.janelia.saalfeldlab.paintera.PainteraCommandLineArgs", usageHelpWidth = 120,
		parameterListHeading = "%n@|bold,underline Parameters|@:%n",
		optionListHeading = "%n@|bold,underline Options|@:%n")
public class PainteraCommandLineArgs implements Callable<Boolean> {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final int DEFAULT_NUM_SCREEN_SCALES = 5;
  private static final double DEFAULT_HIGHEST_SCREEN_SCALE = 1.0;
  private static final double DEFAULT_SCREEN_SCALE_FACTOR = 0.5;

  @Option(names = {"--log-level"})
  private final Level logLevel = null;

  @Option(names = {"--log-level-for"}, split = ",")
  private final Map<String, Level> logLevelsByName = null;

  @ArgGroup(exclusive = false, multiplicity = "0..*")
  private final AddDatasetArgument[] n5datasets = null;

  @Option(names = {"--width"}, paramLabel = "WIDTH", showDefaultValue = CommandLine.Help.Visibility.NEVER)
  private int width = -1;

  @Option(names = {"--height"}, paramLabel = "HEIGHT", showDefaultValue = CommandLine.Help.Visibility.NEVER)
  private int height = -1;

  @Option(names = {"-h", "--help"}, usageHelp = true)
  private boolean helpRequested;

  @Option(names = "--num-screen-scales", paramLabel = "NUM_SCREEN_SCALES")
  private Integer numScreenScales;

  @Option(names = "--highest-screen-scale", paramLabel = "HIGHEST_SCREEN_SCALE")
  private Double highestScreenScale;

  @Option(names = "--screen-scale-factor", paramLabel = "SCREEN_SCALE_FACTOR")
  private Double screenScaleFactor;

  @Option(names = "--screen-scales", paramLabel = "SCREEN_SCALES", arity = "1..*", split = ",")
  private double[] screenScales;

  @Parameters(index = "0", paramLabel = "PROJECT", arity = "0..1", descriptionKey = "project")
  private String project;

  @Option(names = "--print-error-codes", paramLabel = "PRINT_ERROR_CODES")
  private Boolean printErrorCodes;

  @Option(names = "--default-to-temp-directory", paramLabel = "DEFAULT_TO_TEMP_DIRECTORY")
  private Boolean defaultToTempDirectory;

  @Option(names = "--version", paramLabel = "PRINT_VERSION_STRING")
  private Boolean printVersionString;

  private boolean screenScalesProvided = false;

  private static double[] createScreenScales(final int numScreenScales, final double highestScreenScale, final
  double screenScaleFactor)
		  throws ZeroLengthScreenScales {

	if (numScreenScales <= 1) {
	  throw new ZeroLengthScreenScales();
	}

	final double[] screenScales = new double[numScreenScales];
	screenScales[0] = highestScreenScale;
	for (int i = 1; i < screenScales.length; ++i) {
	  screenScales[i] = screenScaleFactor * screenScales[i - 1];
	}
	LOG.debug("Returning screen scales {}", screenScales);
	return screenScales;
  }

  private static void checkScreenScales(final double[] screenScales)
		  throws ZeroLengthScreenScales, InvalidScreenScaleValue, ScreenScaleNotDecreasing {

	if (screenScales.length == 0) {
	  throw new ZeroLengthScreenScales();
	}

	if (screenScales[0] <= 0 || screenScales[0] > 1) {
	  throw new InvalidScreenScaleValue(screenScales[0]);
	}

	for (int i = 1; i < screenScales.length; ++i) {
	  final double prev = screenScales[i - 1];
	  final double curr = screenScales[i];
	  // no check for > 1 necessary because already checked for
	  // monotonicity
	  if (curr <= 0) {
		throw new InvalidScreenScaleValue(curr);
	  }
	  if (prev <= curr) {
		throw new ScreenScaleNotDecreasing(prev, curr);
	  }
	}

  }

  private static <T> T getIfInRange(final T[] array, final int index) {

	return index < array.length ? array[index] : null;
  }

  private static IdService findMaxIdForIdServiceAndWriteToN5(
		  final N5Writer n5,
		  final String dataset,
		  final DataSource<? extends IntegerType<?>, ?> source) throws IOException {

	final long maxId = Math.max(findMaxId(source), 0);
	n5.setAttribute(dataset, N5Helpers.MAX_ID_KEY, maxId);
	return new N5IdService(n5, dataset, maxId + 1);
  }

  private static long findMaxId(final DataSource<? extends IntegerType<?>, ?> source) {

	final RandomAccessibleInterval<? extends IntegerType<?>> rai = source.getDataSource(0, 0);

	final int[] blockSize = blockSizeFromRai(rai);
	final List<Interval> intervals = Grids.collectAllContainedIntervals(Intervals.minAsLongArray(rai), Intervals.maxAsLongArray(rai), blockSize);

	final ExecutorService es = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
	final List<Future<Long>> futures = new ArrayList<>();
	for (final Interval interval : intervals) {
	  futures.add(es.submit(() -> findMaxId(Views.interval(rai, interval))));
	}
	es.shutdown();
	final long maxId = futures
			.stream()
			.map(ThrowingFunction.unchecked(Future::get))
			.mapToLong(Long::longValue)
			.max()
			.orElse(Label.getINVALID());
	LOG.debug("Found max id {}", maxId);
	return maxId;
  }

  private static long findMaxId(final RandomAccessibleInterval<? extends IntegerType<?>> rai) {

	long maxId = org.janelia.saalfeldlab.labels.Label.getINVALID();
	for (final IntegerType<?> t : Views.iterable(rai)) {
	  final long id = t.getIntegerLong();
	  if (id > maxId)
		maxId = id;
	}
	return maxId;
  }

  private static int[] blockSizeFromRai(final RandomAccessibleInterval<?> rai) {

	if (rai instanceof AbstractCellImg<?, ?, ?, ?>) {
	  final CellGrid cellGrid = ((AbstractCellImg<?, ?, ?, ?>)rai).getCellGrid();
	  final int[] blockSize = new int[cellGrid.numDimensions()];
	  cellGrid.cellDimensions(blockSize);
	  LOG.debug("{} is a cell img with block size {}", rai, blockSize);
	  return blockSize;
	}
	final int argMaxDim = argMaxDim(rai);
	final int[] blockSize = Intervals.dimensionsAsIntArray(rai);
	blockSize[argMaxDim] = 1;
	return blockSize;
  }

  private static int argMaxDim(final Dimensions dims) {

	long max = -1;
	int argMax = -1;
	for (int d = 0; d < dims.numDimensions(); ++d) {
	  if (dims.dimension(d) > max) {
		max = dims.dimension(d);
		argMax = d;
	  }
	}
	return argMax;
  }

  private static void addToViewer(
		  final PainteraBaseView viewer,
		  final Supplier<String> projectDirectory,
		  final MetadataState metadataState,
		  final boolean reverseArrayAttributes, //TODO meta to be consistent with previous code, we should reverse the axis order if this is true (should we?)
		  final int channelDimension,
		  long[][] channels,
		  final IdServiceFallbackGenerator idServiceFallback,
		  final LabelBlockLookupFallbackGenerator labelBlockLookupFallback,
		  String name) throws IOException {

	final boolean isLabelData = metadataState.isLabel();
	DatasetAttributes attributes = metadataState.getDatasetAttributes();
	final boolean isChannelData = !isLabelData && attributes.getNumDimensions() == 4;

	if (isLabelData) {
	  viewer.addState((SourceState<?, ?>)makeLabelState(viewer, projectDirectory, metadataState, name));
	} else if (isChannelData) {
	  channels = channels == null ? new long[][]{PainteraCommandLineArgs.range((int)attributes.getDimensions()[channelDimension])} : channels;
	  final String fname = name;
	  final Function<long[], String> nameBuilder = channels.length == 1
			  ? c -> fname
			  : c -> String.format("%s-%s", fname, Arrays.toString(c));
	  for (final long[] channel : channels) {
		viewer.addState(makeChannelSourceState(viewer, metadataState, channelDimension, channel, nameBuilder.apply(channel)));
	  }
	} else {
	  viewer.addState((SourceState<?, ?>)makeRawSourceState(viewer, metadataState, name));
	}
  }

  private static <D extends NativeType<D> & IntegerType<D>, T extends Volatile<D> & NativeType<T>> ConnectomicsLabelState<D, T> makeLabelState(
		  final PainteraBaseView viewer,
		  final Supplier<String> projectDirectory,
		  final MetadataState metadataState,
		  final String name) {

	final N5Backend<D, T> backend = N5Backend.createFrom(metadataState, projectDirectory, viewer.getPropagationQueue());
	return new ConnectomicsLabelState<>(
			backend,
			viewer.viewer3D().meshesGroup(),
			viewer.viewer3D().viewFrustumProperty(),
			viewer.viewer3D().eyeToWorldTransformProperty(),
			viewer.getMeshManagerExecutorService(),
			viewer.getMeshWorkerExecutorService(),
			viewer.getQueue(),
			0, // TODO is this the right priority?
			name,
			null);
  }

  private static <D extends RealType<D> & NativeType<D>, T extends AbstractVolatileRealType<D, T> & NativeType<T>> SourceState<D, T> makeRawSourceState(
		  final PainteraBaseView viewer,
		  MetadataState metadataState,
		  final String name
  ) {

	final N5BackendRaw<D, T> backend = new N5BackendRaw<>(metadataState);
	final ConnectomicsRawState<D, T> state = new ConnectomicsRawState<>(
			backend,
			viewer.getQueue(),
			0,
			name);
	state.converter().setMin(metadataState.getMinIntensity());
	state.converter().setMax(metadataState.getMaxIntensity());
	return state;
  }

  private static <D extends RealType<D> & NativeType<D>, T extends AbstractVolatileRealType<D, T> & NativeType<T>> ChannelSourceState<D, T, ?, ?> makeChannelSourceState(
		  final PainteraBaseView viewer,
		  MetadataState metadataState,
		  final int channelDimension,
		  final long[] channels,
		  final String name
  ) throws IOException {

	try {
	  final N5ChannelDataSourceMetadata<D, T> channelSource = N5ChannelDataSourceMetadata.zeroExtended(
			  metadataState,
			  name,
			  viewer.getQueue(),
			  0,
			  channelDimension,
			  channels);
	  return new ChannelSourceState<>(
			  channelSource,
			  new ARGBCompositeColorConverter.InvertingImp0<>(channels.length, metadataState.getMinIntensity(), metadataState.getMaxIntensity()),
			  new ARGBCompositeAlphaAdd(),
			  name);
	} catch (final DataTypeNotSupported e) {
	  throw new IOException(e);
	}
  }

  private static <T> T getLastEntry(final T[] array) {

	return array.length > 0 ? array[array.length - 1] : null;
  }

  private static long[] range(final int N) {

	final long[] range = new long[N];
	Arrays.setAll(range, d -> d);
	return range;
  }

  private static String[] datasetsAsRawChannelLabel(final N5Reader n5, final Collection<String> datasets) throws IOException {

	final List<String> rawDatasets = new ArrayList<>();
	final List<String> channelDatasets = new ArrayList<>();
	final List<String> labelDatsets = new ArrayList<>();
	for (final String dataset : datasets) {
	  final DatasetAttributes attributes = N5Helpers.getDatasetAttributes(n5, dataset);
	  if (attributes.getNumDimensions() == 4)
		channelDatasets.add(dataset);
	  else if (attributes.getNumDimensions() == 3) {
		if (
				N5Helpers.isPainteraDataset(n5, dataset) && n5.getAttribute(dataset, N5Helpers.PAINTERA_DATA_KEY, JsonObject.class).get("type").getAsString()
						.equals("label") ||
						N5Types.isLabelData(attributes.getDataType(), N5Types.isLabelMultisetType(n5, dataset)))
		  labelDatsets.add(dataset);
		else
		  rawDatasets.add(dataset);
	  }
	}
	return Stream.of(rawDatasets, channelDatasets, labelDatsets).flatMap(List::stream).toArray(String[]::new);
  }

  @Override
  public Boolean call() throws Exception {

	LogUtils.setRootLoggerLevel(logLevel == null ? Level.INFO : logLevel);

	width = width <= 0 ? -1 : width;
	height = height <= 0 ? -1 : height;

	screenScalesProvided = screenScales != null || numScreenScales != null || highestScreenScale != null || screenScaleFactor != null;

	numScreenScales = Optional.ofNullable(this.numScreenScales).filter(n -> n > 0).orElse(DEFAULT_NUM_SCREEN_SCALES);
	highestScreenScale = Optional.ofNullable(highestScreenScale).filter(s -> s > 0 && s <= 1).orElse(DEFAULT_HIGHEST_SCREEN_SCALE);
	screenScaleFactor = Optional.ofNullable(screenScaleFactor).filter(f -> f > 0 && f < 1).orElse(DEFAULT_SCREEN_SCALE_FACTOR);
	screenScales = screenScales == null
			? createScreenScales(numScreenScales, highestScreenScale, screenScaleFactor)
			: screenScales;

	if (screenScales != null) {
	  checkScreenScales(screenScales);
	}

	printErrorCodes = printErrorCodes == null ? false : printErrorCodes;
	if (printErrorCodes) {
	  LOG.info("Error codes:");
	  for (final Error error : Error.values()) {
		LOG.info("{} -- {}", error.getCode(), error.getDescription());
	  }
	  return false;
	}

	printVersionString = printVersionString == null ? false : printVersionString;
	if (printVersionString) {
	  System.out.println(Version.VERSION_STRING);
	  return false;
	}

	if (defaultToTempDirectory != null)
	  LOG.warn("The --default-to-temp-directory flag was deprecated and will be removed in a future release.");

	defaultToTempDirectory = defaultToTempDirectory == null ? false : defaultToTempDirectory;

	return true;
  }

  public int width(final int defaultWidth) {

	return width <= 0 ? defaultWidth : width;
  }

  public int height(final int defaultHeight) {

	return height <= 0 ? defaultHeight : height;
  }

  public String project() {

	final String returnedProject = this.project == null ? this.project : new File(project).getAbsolutePath();
	LOG.debug("Return project={}", returnedProject);
	return returnedProject;
  }

  public double[] screenScales() {

	return this.screenScales.clone();
  }

  public boolean defaultToTempDirectory() {

	return this.defaultToTempDirectory;
  }

  public boolean wereScreenScalesProvided() {

	return this.screenScalesProvided;
  }

  public Level getLogLevel() {

	return this.logLevel;
  }

  public Map<String, Level> getLogLevelsByName() {

	return this.logLevelsByName == null ? Collections.emptyMap() : this.logLevelsByName;
  }

  public void addToViewer(final PainteraBaseView viewer, final Supplier<String> projectDirectory) {

	if (this.n5datasets == null)
	  return;
	Stream.of(this.n5datasets).forEach(ThrowingConsumer.unchecked(ds -> ds.addToViewer(viewer, projectDirectory)));
  }

  private enum IdServiceFallback {
	ASK(PainteraAlerts::getN5IdServiceFromData),
	FROM_DATA(PainteraCommandLineArgs::findMaxIdForIdServiceAndWriteToN5),
	NONE((n5, dataset, source) -> new IdService.IdServiceNotProvided());

	private final IdServiceFallbackGenerator idServiceGenerator;

	IdServiceFallback(final IdServiceFallbackGenerator idServiceGenerator) {

	  this.idServiceGenerator = idServiceGenerator;
	}

	public IdServiceFallbackGenerator getIdServiceGenerator() {

	  LOG.debug("Getting id service generator from {}", this);
	  return this.idServiceGenerator;
	}

	private static class TypeConverter implements CommandLine.ITypeConverter<IdServiceFallback> {

	  @Override
	  public IdServiceFallback convert(final String s) throws NoMatchFound {

		try {
		  return IdServiceFallback.valueOf(s.replace("-", "_").toUpperCase());
		} catch (final IllegalArgumentException e) {
		  throw new NoMatchFound(s, e);
		}
	  }

	  private static class NoMatchFound extends Exception {

		private final String selection;

		private NoMatchFound(final String selection, final Throwable e) {

		  super(
				  String.format(
						  "No match found for selection `%s'. Pick any of these options (case insensitive): %s",
						  selection,
						  Arrays.asList(IdServiceFallback.values())),
				  e);
		  this.selection = selection;
		}
	  }
	}
  }

  private enum LabelBlockLookupFallback {
	ASK(PainteraAlerts::getLabelBlockLookupFromN5DataSource),
	NONE((c, g, s) -> new LabelBlockLookupNoBlocks()),
	COMPLETE((c, g, s) -> LabelBlockLookupAllBlocks.fromSource(s));

	private final LabelBlockLookupFallbackGenerator generator;

	LabelBlockLookupFallback(final LabelBlockLookupFallbackGenerator generator) {

	  this.generator = generator;
	}

	public LabelBlockLookupFallbackGenerator getGenerator() {

	  return this.generator;
	}

	private static class TypeConverter implements CommandLine.ITypeConverter<LabelBlockLookupFallback> {

	  @Override
	  public LabelBlockLookupFallback convert(final String s) throws TypeConverter.NoMatchFound {

		try {
		  return LabelBlockLookupFallback.valueOf(s.replace("-", "_").toUpperCase());
		} catch (final IllegalArgumentException e) {
		  throw new TypeConverter.NoMatchFound(s, e);
		}
	  }

	  private static class NoMatchFound extends Exception {

		private final String selection;

		private NoMatchFound(final String selection, final Throwable e) {

		  super(
				  String.format(
						  "No match found for selection `%s'. Pick any of these options (case insensitive): %s",
						  selection,
						  Arrays.asList(LabelBlockLookupFallback.values())),
				  e);
		  this.selection = selection;
		}
	  }
	}
  }

  private interface IdServiceFallbackGenerator {

	IdService get(
			final N5Writer n5,
			final String dataset,
			final DataSource<? extends IntegerType<?>, ?> source) throws IOException;
  }

  private interface LabelBlockLookupFallbackGenerator {

	LabelBlockLookup get(
			final N5Reader n5,
			final String group,
			final DataSource<?, ?> source);
  }

  private static class LongArrayTypeConverter implements CommandLine.ITypeConverter<long[]> {

	@Override
	public long[] convert(final String value) {

	  return Stream
			  .of(value.split(","))
			  .mapToLong(Long::parseLong)
			  .toArray();
	}
  }

  private static final class AddDatasetArgument {

	private static ExecutorService DISCOVERY_EXECUTOR_SERVICE = null;
	@Option(names = "--add-n5-container", arity = "1..*", required = true)
	private final String[] container = null;
	@ArgGroup(multiplicity = "1", exclusive = false)
	private final Options options = null;

	private static synchronized ExecutorService getDiscoveryExecutorService() {

	  if (DISCOVERY_EXECUTOR_SERVICE == null || DISCOVERY_EXECUTOR_SERVICE.isShutdown()) {
		DISCOVERY_EXECUTOR_SERVICE = Executors.newFixedThreadPool(
				12,
				new NamedThreadFactory("dataset-discovery-%d", true));
		LOG.debug("Created discovery executor service {}", DISCOVERY_EXECUTOR_SERVICE);
	  }
	  return DISCOVERY_EXECUTOR_SERVICE;
	}

	private void addToViewer(final PainteraBaseView viewer, final Supplier<String> projectDirectory) throws IOException {

	  if (options == null)
		return;

	  if (options.datasets == null && !options.addEntireContainer) {
		LOG.warn("" +
				"No datasets will be added: " +
				"--add-n5-container was specified but no dataset was provided through the -d, --dataset option. " +
				"To add all datasets of a container, please set the --entire-container option and use the " +
				"--exclude and --include options.");
		return;
	  }

	  if (container == null && projectDirectory == null) {
		LOG.warn("Will not add any datasets: " +
				"No container or project directory specified.");
		return;
	  }

	  final String[] containers = container == null
			  ? new String[]{projectDirectory.get()}
			  : container;

	  for (final String container : containers) {
		LOG.debug("Adding datasets for container {}", container);

		N5Writer writer = null;
		N5Reader reader;

		try {
		  writer = N5Helpers.n5Writer(container);
		  reader = writer;
		} catch (IOException e) {
		  reader = N5Helpers.n5Reader(container);
		}

		final Predicate<String> datasetFilter = options.useDataset();
		final ExecutorService es = getDiscoveryExecutorService();
		final String[] datasets;
		if (options.addEntireContainer) {
		  Optional<N5TreeNode> rootNode = N5Helpers.parseMetadata(reader, es);
		  if (rootNode.isPresent()) {
			final List<String> validGroups = N5Helpers.validPainteraGroupMap(rootNode.get()).keySet().stream()
					.filter(datasetFilter)
					.collect(Collectors.toList());
			datasets = datasetsAsRawChannelLabel(reader, validGroups);
		  } else {
			datasets = new String[]{};
		  }
		} else {
		  datasets = options.datasets;
		}
		final String[] names = options.addEntireContainer
				? null
				: options.name;
		for (int index = 0; index < datasets.length; ++index) {
		  final String dataset = datasets[index];

		  if (!reader.exists(dataset)) {
			LOG.warn("Group {} does not exist in container {}", dataset, reader);
			return;
		  }

		  final var containerState = new N5ContainerState(container, reader, writer);
		  final var metadataOpt = N5Helpers.parseMetadata(reader)
				  .flatMap(tree -> N5TreeNode.flattenN5Tree(tree).filter(node -> node.getPath().equals(dataset)).findFirst())
				  .filter(node -> MetadataUtils.metadataIsValid(node.getMetadata()))
				  .map(N5TreeNode::getMetadata)
				  .flatMap(md -> MetadataUtils.createMetadataState(containerState, md));

		  if (metadataOpt.isEmpty()) {
			LOG.warn("Group " + dataset + " from " + container + " cannot be parsed");
			return;
		  }

		  //TODO meta take resolution/offset into account
		  final var metadataState = metadataOpt.get();

		  metadataState.updateTransform(options.resolution, options.offset);
		  metadataState.setMinIntensity(options.min);
		  metadataState.setMaxIntensity(options.max);

		  PainteraCommandLineArgs.addToViewer(
				  viewer,
				  projectDirectory,
				  metadataState,
				  options.reverseArrayAttributes,
				  options.channelDimension,
				  options.channels,
				  options.idServiceFallback.getIdServiceGenerator(),
				  options.labelBlockLookupFallback.getGenerator(),
				  names == null ? metadataState.getGroup() : getIfInRange(names, index));
		}
	  }
	}

	private static final class Options {

	  @Option(names = {"--min"}, paramLabel = "MIN")
	  private final Double min = null;

	  @Option(names = {"--max"}, paramLabel = "MAX")
	  private final Double max = null;

	  @Option(names = {"--channel-dimension"}, defaultValue = "3", paramLabel = "CHANNEL_DIMENSION")
	  private final Integer channelDimension = 3;

	  @Option(names = {"--channels"}, paramLabel = "CHANNELS", arity = "1..*", converter = LongArrayTypeConverter.class)
	  private final long[][] channels = null;

	  @Option(names = {"-d", "--dataset"}, paramLabel = "DATASET", arity = "1..*", required = true)
	  String[] datasets = null;

	  @Option(names = {"-r", "--resolution"}, paramLabel = "RESOLUTION", split = ",")
	  double[] resolution = new double[]{1.0, 1.0, 1.0};

	  @Option(names = {"-o", "--offset"}, paramLabel = "OFFSET", split = ",")
	  double[] offset = new double[]{0.0, 0.0, 0.0};

	  @Option(names = {"-R", "--reverse-array-attributes"}, paramLabel = "REVERT")
	  Boolean reverseArrayAttributes = false; //FIXME shouldn't this be reverse, not reverse?

	  @Option(names = {"--name"}, paramLabel = "NAME")
	  String[] name = null;

	  @Option(names = {"--id-service-fallback"}, paramLabel = "ID_SERVICE_FALLBACK", defaultValue = "ask", converter = IdServiceFallback.TypeConverter.class)
	  IdServiceFallback idServiceFallback = null;

	  @Option(names = {"--label-block-lookup-fallback"}, paramLabel = "LABEL_BLOCK_LOOKUP_FALLBACK", defaultValue = "ask", converter = LabelBlockLookupFallback.TypeConverter.class)
	  LabelBlockLookupFallback labelBlockLookupFallback = null;

	  @Option(names = {"--entire-container"}, paramLabel = "ENTIRE_CONTAINER", defaultValue = "false")
	  Boolean addEntireContainer = null;

	  @Option(names = {"--exclude"}, paramLabel = "EXCLUDE", arity = "1..*")
	  String[] exclude = null;

	  @Option(names = {"--include"}, paramLabel = "INCLUDE", arity = "1..*")
	  String[] include = null;

	  @Option(names = {"--only-explicitly-included"})
	  Boolean onlyExplicitlyIncluded = false;

	  private Predicate<String> isIncluded() {

		LOG.debug("Creating include pattern matcher for patterns {}", (Object)this.include);
		if (this.include == null)
		  return s -> false;
		final Pattern[] patterns = Stream.of(this.include).map(Pattern::compile).toArray(Pattern[]::new);
		return s -> {
		  for (final Pattern p : patterns) {
			if (p.matcher(s).matches())
			  return true;
		  }
		  return false;
		};
	  }

	  private Predicate<String> isExcluded() {

		LOG.debug("Creating exclude pattern matcher for patterns {}", (Object)this.exclude);
		if (this.exclude == null)
		  return s -> false;
		final Pattern[] patterns = Stream.of(this.exclude).map(Pattern::compile).toArray(Pattern[]::new);
		return s -> {
		  for (final Pattern p : patterns) {
			if (p.matcher(s).matches()) {
			  LOG.debug("Excluded: Pattern {} matched {}", p, s);
			  return true;
			}
		  }
		  return false;
		};
	  }

	  private Predicate<String> isOnlyExplicitlyIncluded() {

		return s -> {
		  LOG.debug("Is only explicitly included? {}", onlyExplicitlyIncluded);
		  return onlyExplicitlyIncluded;
		};
	  }

	  private Predicate<String> useDataset() {

		return isIncluded().or((isExcluded().or(isOnlyExplicitlyIncluded())).negate());
	  }
	}
  }

  public static class ZeroLengthScreenScales extends Exception {

  }

  public static class InvalidScreenScaleValue extends Exception {

	InvalidScreenScaleValue(final double scale) {

	  super("Screen scale " + scale + " not in legal interval (0,1]");
	}
  }

  public static class ScreenScaleNotDecreasing extends Exception {

	public ScreenScaleNotDecreasing(final double first, final double second) {

	  super("Second screen scale " + second + " larger than or equal to first " + first);
	}
  }

}
