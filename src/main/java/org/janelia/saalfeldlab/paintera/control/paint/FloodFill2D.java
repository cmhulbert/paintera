package org.janelia.saalfeldlab.paintera.control.paint;

import bdv.fx.viewer.ViewerPanelFX;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import net.imglib2.FinalInterval;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.algorithm.fill.FloodFill;
import net.imglib2.algorithm.neighborhood.DiamondShape;
import net.imglib2.converter.Converters;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.logic.BoolType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.util.AccessBoxRandomAccessibleOnGet;
import net.imglib2.util.Intervals;
import net.imglib2.view.MixedTransformView;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.paintera.Paintera;
import org.janelia.saalfeldlab.paintera.control.assignment.FragmentSegmentAssignment;
import org.janelia.saalfeldlab.paintera.data.mask.MaskInfo;
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource;
import org.janelia.saalfeldlab.paintera.data.mask.SourceMask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class FloodFill2D<T extends IntegerType<T>> {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final ViewerPanelFX viewer;

  private final MaskedSource<T, ?> source;

  private final FragmentSegmentAssignment assignment;

  private final BooleanSupplier isVisible;

  private final SimpleDoubleProperty fillDepth = new SimpleDoubleProperty(1.0);

  private static final long FILL_VALUE = 1L;

  private static final class ForegroundCheck implements Predicate<UnsignedLongType> {

	@Override
	public boolean test(final UnsignedLongType t) {

	  return t.getIntegerLong() == FILL_VALUE;
	}
  }

  private static final ForegroundCheck FOREGROUND_CHECK = new ForegroundCheck();

  public FloodFill2D(
		  final ViewerPanelFX viewer,
		  final MaskedSource<T, ?> source,
		  final FragmentSegmentAssignment assignment,
		  final BooleanSupplier isVisible) {

	super();
	Objects.requireNonNull(viewer);
	Objects.requireNonNull(source);
	Objects.requireNonNull(assignment);
	Objects.requireNonNull(isVisible);

	this.viewer = viewer;
	this.source = source;
	this.assignment = assignment;
	this.isVisible = isVisible;
  }

  public void fillAt(final double x, final double y, final Supplier<Long> fillSupplier) {

	final Long fill = fillSupplier.get();
	if (fill == null) {
	  LOG.info("Received invalid label {} -- will not fill.", fill);
	  return;
	}
	fillAt(x, y, fill);
  }

  public void fillAt(final double x, final double y, final long fill) {

	if (!isVisible.getAsBoolean()) {
	  LOG.info("Selected source is not visible -- will not fill");
	  return;
	}

	final int level = 0;
	final int time = 0;
	final MaskInfo maskInfo = new MaskInfo(time, level, new UnsignedLongType(fill));

	final Scene scene = viewer.getScene();
	final Cursor previousCursor = scene.getCursor();
	scene.setCursor(Cursor.WAIT);
	try {
	  final ViewerMask mask = ViewerMask.setNewViewerMask(source, maskInfo, viewer, this.fillDepth.get());
	  final Interval affectedViewerInterval = fillViewerMaskAt(x, y, this.viewer, mask, source, assignment, FILL_VALUE);
	  final Interval affectedSourceInterval = Intervals.smallestContainingInterval(mask.getLabelToViewerTransform().inverse().estimateBounds(affectedViewerInterval));
	  //	  final SourceMask mask = source.generateMask(maskInfo, FOREGROUND_CHECK);
	  //	  final Interval affectedInterval = fillMaskAt(x, y, this.viewer, mask, source, assignment, FILL_VALUE, this.fillDepth.get());
	  source.applyMask(source.getCurrentMask(), affectedSourceInterval, FOREGROUND_CHECK); //, true);
	  final Interval affectedGlobalInterval = Intervals.smallestContainingInterval(mask.getLabelToGlobalTransform().estimateBounds(affectedSourceInterval));
	  Paintera.getPaintera().getBaseView().orthogonalViews().requestRepaint(affectedGlobalInterval);
	  //	} catch (final MaskInUse e) {
	  //	  LOG.debug(e.getMessage());
	} finally {
	  scene.setCursor(previousCursor);
	}
  }

  /**
   * Flood-fills the given mask starting at the specified 2D location in the viewer.
   * Returns the affected interval in source coordinates.
   *
   * @param x
   * @param y
   * @param viewer
   * @param mask
   * @param source
   * @param assignment
   * @param fillValue
   * @param fillDepth
   * @return affected interval
   */
  public static <T extends IntegerType<T>> Interval fillMaskAt(
		  final double x,
		  final double y,
		  final ViewerPanelFX viewer,
		  final SourceMask mask,
		  final MaskedSource<T, ?> source,
		  final FragmentSegmentAssignment assignment,
		  final long fillValue,
		  final double fillDepth) {

	final AffineTransform3D labelTransform = source.getSourceTransformForMask(mask.getInfo());
	final RandomAccessibleInterval<T> background = source.getDataSourceForMask(mask.getInfo());

	final RandomAccess<T> access = background.randomAccess();
	final RealPoint posInLabelSpace = new RealPoint(access.numDimensions());
	viewer.displayToSourceCoordinates(x, y, labelTransform, posInLabelSpace);
	for (int d = 0; d < access.numDimensions(); ++d) {
	  access.setPosition(Math.round(posInLabelSpace.getDoublePosition(d)), d);
	}
	final long seedLabel = assignment != null ? assignment.getSegment(access.get().getIntegerLong()) : access.get().getIntegerLong();
	LOG.debug("Got seed label {}", seedLabel);
	final RandomAccessibleInterval<BoolType> relevantBackground = Converters.convert(
			background,
			(src, tgt) -> tgt.set((assignment != null ? assignment.getSegment(src.getIntegerLong()) : src.getIntegerLong()) == seedLabel),
			new BoolType()
	);

	return fillMaskAt(x, y, viewer, mask, relevantBackground, labelTransform, fillValue, fillDepth);
  }

  /**
   * Flood-fills the given mask starting at the specified 2D location in the viewer
   * based on the given boolean filter.
   * Returns the affected interval in source coordinates.
   *
   * @param x
   * @param y
   * @param viewer
   * @param mask
   * @param filter
   * @param labelTransform
   * @param fillValue
   * @param fillDepth
   * @return affected interval
   */
  public static <T extends IntegerType<T>> Interval fillMaskAt(
		  final double x,
		  final double y,
		  final ViewerPanelFX viewer,
		  final SourceMask mask,
		  final RandomAccessibleInterval<BoolType> filter,
		  final AffineTransform3D labelTransform,
		  final long fillValue,
		  final double fillDepth) {

	final AffineTransform3D viewerTransform = new AffineTransform3D();
	viewer.getState().getViewerTransform(viewerTransform);
	final AffineTransform3D labelToViewerTransform = viewerTransform.copy().concatenate(labelTransform);

	final RealPoint pos = new RealPoint(labelTransform.numDimensions());
	viewer.displayToSourceCoordinates(x, y, labelTransform, pos);

	final RandomAccessible<BoolType> extendedFilter = Views.extendValue(filter, new BoolType(false));

	final int fillNormalAxisInLabelCoordinateSystem = PaintUtils.labelAxisCorrespondingToViewerAxis(labelTransform, viewerTransform, 2);
	final AccessBoxRandomAccessibleOnGet<UnsignedLongType> accessTracker = new
			AccessBoxRandomAccessibleOnGet<>(
			Views.extendValue(mask.getRai(), new UnsignedLongType(fillValue)));
	accessTracker.initAccessBox();

	if (fillNormalAxisInLabelCoordinateSystem < 0) {
	  FloodFillTransformedPlane.fill(
			  labelToViewerTransform,
			  (fillDepth - 0.5) * PaintUtils.maximumVoxelDiagonalLengthPerDimension(
					  labelTransform,
					  viewerTransform
			  )[2],
			  extendedFilter.randomAccess(),
			  accessTracker.randomAccess(),
			  new RealPoint(x, y, 0),
			  fillValue
	  );
	} else {
	  LOG.debug(
			  "Flood filling axis aligned. Corressponding viewer axis={}",
			  fillNormalAxisInLabelCoordinateSystem
	  );
	  final long slicePos = Math.round(pos.getDoublePosition(fillNormalAxisInLabelCoordinateSystem));
	  final long numSlices = Math.max((long)Math.ceil(fillDepth) - 1, 0);
	  if (numSlices == 0) {
		// fill only within the given slice, run 2D flood-fill
		final long[] seed2D = {
				Math.round(pos.getDoublePosition(fillNormalAxisInLabelCoordinateSystem == 0 ? 1 : 0)),
				Math.round(pos.getDoublePosition(fillNormalAxisInLabelCoordinateSystem != 2 ? 2 : 1))
		};
		final MixedTransformView<BoolType> relevantBackgroundSlice = Views.hyperSlice(
				extendedFilter,
				fillNormalAxisInLabelCoordinateSystem,
				slicePos
		);
		final MixedTransformView<UnsignedLongType> relevantAccessTracker = Views.hyperSlice(
				accessTracker,
				fillNormalAxisInLabelCoordinateSystem,
				slicePos
		);
		FloodFill.fill(
				relevantBackgroundSlice,
				relevantAccessTracker,
				new Point(seed2D),
				new UnsignedLongType(fillValue),
				new DiamondShape(1)
		);
	  } else {
		// fill a range around the given slice, run 3D flood-fill restricted by this range
		final long[] seed3D = new long[3];
		Arrays.setAll(seed3D, d -> Math.round(pos.getDoublePosition(d)));

		final long[] rangeMin = Intervals.minAsLongArray(filter);
		final long[] rangeMax = Intervals.maxAsLongArray(filter);
		rangeMin[fillNormalAxisInLabelCoordinateSystem] = slicePos - numSlices;
		rangeMax[fillNormalAxisInLabelCoordinateSystem] = slicePos + numSlices;
		final Interval range = new FinalInterval(rangeMin, rangeMax);

		final RandomAccessible<BoolType> extendedBackgroundRange = Views.extendValue(
				Views.interval(extendedFilter, range),
				new BoolType(false)
		);
		FloodFill.fill(
				extendedBackgroundRange,
				accessTracker,
				new Point(seed3D),
				new UnsignedLongType(fillValue),
				new DiamondShape(1)
		);
	  }
	}

	return new FinalInterval(accessTracker.getMin(), accessTracker.getMax());
  }

  public DoubleProperty fillDepthProperty() {

	return this.fillDepth;
  }

  public static <T extends IntegerType<T>> Interval fillViewerMaskAt(
		  final double x,
		  final double y,
		  final ViewerPanelFX viewer,
		  final ViewerMask mask,
		  final MaskedSource<T, ?> source,
		  final FragmentSegmentAssignment assignment,
		  final long fillValue) {

	final AffineTransform3D labelTransform = source.getSourceTransformForMask(mask.getInfo());
	final RandomAccessibleInterval<T> background = source.getDataSourceForMask(mask.getInfo());
	final RandomAccess<T> sourceAccess = background.randomAccess();
	final RealPoint posInSourceSpace = new RealPoint(sourceAccess.numDimensions());
	viewer.displayToSourceCoordinates(x, y, labelTransform, posInSourceSpace);
	for (int d = 0; d < sourceAccess.numDimensions(); ++d) {
	  sourceAccess.setPosition(Math.round(posInSourceSpace.getDoublePosition(d)), d);
	}
	final long seedLabel = assignment != null ? assignment.getSegment(sourceAccess.get().getIntegerLong()) : sourceAccess.get().getIntegerLong();
	LOG.debug("Got seed label {}", seedLabel);
	final RandomAccessibleInterval<BoolType> backgroundLabelMask = Converters.convert(
			background,
			(src, tgt) -> tgt.set((assignment != null ? assignment.getSegment(src.getIntegerLong()) : src.getIntegerLong()) == seedLabel),
			new BoolType()
	);

	/* transform background to viewer */
	final Interval viewerInterval = mask.getViewerRai();
	final var sourceIntervalMin = new RealPoint(sourceAccess.numDimensions());
	viewer.displayToSourceCoordinates(0, 0, labelTransform, sourceIntervalMin);
	final var sourceIntervalMax = new RealPoint(sourceAccess.numDimensions());
	viewer.displayToSourceCoordinates(viewerInterval.max(0), viewerInterval.max(1), labelTransform, sourceIntervalMax);
	final var sourceInterval = Intervals.smallestContainingInterval(new FinalRealInterval(sourceIntervalMin, sourceIntervalMax));
	final var backgroundOverViewerInterval = Views.zeroMin(Views.interval(backgroundLabelMask, sourceInterval));

	final var realBackgroundLabelMask = Views.interpolate(backgroundOverViewerInterval, new NearestNeighborInterpolatorFactory<>());
	final var backgroundLabelMaskInViewer = Views.interval(Views.raster(RealViews.affineReal(realBackgroundLabelMask, mask.getLabelToViewerTransform())), viewerInterval);

	return fillViewerMaskAt(x, y, mask, backgroundLabelMaskInViewer, fillValue);
  }

  public static <T extends IntegerType<T>> Interval fillViewerMaskAt(
		  final double x,
		  final double y,
		  final ViewerMask mask,
		  final RandomAccessibleInterval<BoolType> filter,
		  final long fillValue) {

	final RandomAccessible<BoolType> extendedFilter = Views.extendValue(filter, new BoolType(false));

	final AccessBoxRandomAccessibleOnGet<UnsignedLongType> sourceAccessTracker = new
			AccessBoxRandomAccessibleOnGet<>(
			Views.extendValue(mask.getViewerRai(), new UnsignedLongType(fillValue)));
	sourceAccessTracker.initAccessBox();

	LOG.debug("Flood filling into viewer mask ");
	// fill only within the given slice, run 2D flood-fill
	final long[] seed2D = {(long)x, (long)y};
	final RandomAccessible<BoolType> backgroundSlice = Views.hyperSlice(
			extendedFilter,
			0,
			0
	);
	final RandomAccessible<UnsignedLongType> viewerFillImg = Views.hyperSlice(
			sourceAccessTracker,
			0,
			0
	);
	FloodFill.fill(
			backgroundSlice,
			viewerFillImg,
			new Point(seed2D),
			new UnsignedLongType(fillValue),
			new DiamondShape(1)
	);

	return new FinalInterval(sourceAccessTracker.getMin(), sourceAccessTracker.getMax());
  }
}
