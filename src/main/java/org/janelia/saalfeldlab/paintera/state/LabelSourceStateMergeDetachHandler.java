package org.janelia.saalfeldlab.paintera.state;

import bdv.fx.viewer.ViewerPanelFX;
import bdv.viewer.Interpolation;
import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.set.hash.TLongHashSet;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.label.Label;
import net.imglib2.type.numeric.IntegerType;
import org.janelia.saalfeldlab.fx.actions.ActionSet;
import org.janelia.saalfeldlab.fx.actions.NamedKeyCombination;
import org.janelia.saalfeldlab.fx.actions.PainteraActionSet;
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys;
import org.janelia.saalfeldlab.paintera.control.actions.LabelActionType;
import org.janelia.saalfeldlab.paintera.control.assignment.FragmentSegmentAssignment;
import org.janelia.saalfeldlab.paintera.control.assignment.action.AssignmentAction;
import org.janelia.saalfeldlab.paintera.control.assignment.action.Detach;
import org.janelia.saalfeldlab.paintera.control.assignment.action.Merge;
import org.janelia.saalfeldlab.paintera.control.selection.SelectedIds;
import org.janelia.saalfeldlab.paintera.data.DataSource;
import org.janelia.saalfeldlab.paintera.id.IdService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

public class LabelSourceStateMergeDetachHandler {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final LongPredicate FOREGROUND_CHECK = Label::isForeground;

  private final DataSource<? extends IntegerType<?>, ?> source;

  private final SelectedIds selectedIds;

  private final FragmentSegmentAssignment assignment;

  private final IdService idService;

  private final HashMap<ViewerPanelFX, EventHandler<Event>> handlers = new HashMap<>();

  public LabelSourceStateMergeDetachHandler(
		  final DataSource<? extends IntegerType<?>, ?> source,
		  final SelectedIds selectedIds,
		  final FragmentSegmentAssignment assignment,
		  final IdService idService) {

	this.source = source;
	this.selectedIds = selectedIds;
	this.assignment = assignment;
	this.idService = idService;
  }

  public List<ActionSet> makeActionSets(NamedKeyCombination.CombinationMap keyBindings, Supplier<ViewerPanelFX> activeViewerSupplier) {

	final var mergeFragments = new PainteraActionSet("merge fragments", LabelActionType.Merge, actionSet -> {
	  actionSet.addMouseAction(MouseEvent.MOUSE_PRESSED, action -> {
		action.keysDown(KeyCode.SHIFT);
		action.verify(MouseEvent::isPrimaryButtonDown);
		action.verify(event -> activeViewerSupplier.get() != null);
		action.onAction(mouseEvent -> new MergeFragments(activeViewerSupplier.get()));
	  });
	  actionSet.addKeyAction(KeyEvent.KEY_PRESSED, action -> {
		action.keyMatchesBinding(keyBindings, LabelSourceStateKeys.MERGE_ALL_SELECTED);
		action.onAction(event -> mergeAllSelected());
	  });
	});
	final var detachFragments = new PainteraActionSet("detach fragment", LabelActionType.Split, actionSet -> {
	  actionSet.addMouseAction(MouseEvent.MOUSE_PRESSED, action -> {
		action.setName("detach fragment");
		action.keysDown(KeyCode.SHIFT);
		action.verify(MouseEvent::isSecondaryButtonDown);
		action.verify(event -> activeViewerSupplier.get() != null);
		action.onAction(mouseEvent -> new DetachFragment(activeViewerSupplier.get()));
	  });
	  actionSet.addMouseAction(MouseEvent.MOUSE_PRESSED, action -> {
		action.setName("confirm selection");
		action.keysDown(KeyCode.SHIFT, KeyCode.CONTROL);
		action.verify(MouseEvent::isSecondaryButtonDown);
		action.verify(event -> activeViewerSupplier.get() != null);
		action.onAction(event -> new ConfirmSelection(activeViewerSupplier.get()));
	  });
	});

	return List.of(mergeFragments, detachFragments);
  }

  private void mergeAllSelected() {
	FragmentSegmentAssignment.mergeAllSelected(assignment, selectedIds, idService);
  }

  private class MergeFragments implements Consumer<MouseEvent> {

	private final ViewerPanelFX viewer;

	private MergeFragments(final ViewerPanelFX viewer) {

	  this.viewer = viewer;
	}

	@Override
	public void accept(final MouseEvent e) {

	  synchronized (viewer) {

		final long lastSelection = selectedIds.getLastSelection();

		if (lastSelection == Label.INVALID) {
		  return;
		}

		final AffineTransform3D screenScaleTransform = new AffineTransform3D();
		viewer.getRenderUnit().getScreenScaleTransform(0, screenScaleTransform);
		final int level = viewer.getState().getBestMipMapLevel(screenScaleTransform, source);

		final AffineTransform3D affine = new AffineTransform3D();
		source.getSourceTransform(0, level, affine);
		final RealRandomAccess<? extends IntegerType<?>> access = RealViews.transformReal(
				source.getInterpolatedDataSource(
						0,
						level,
						Interpolation.NEARESTNEIGHBOR),
				affine).realRandomAccess();
		viewer.getMouseCoordinates(access);
		access.setPosition(0L, 2);
		viewer.displayToGlobalCoordinates(access);
		final IntegerType<?> val = access.get();
		final long id = val.getIntegerLong();

		if (FOREGROUND_CHECK.test(id)) {
		  LOG.debug("Merging fragments: {} -- last selection: {}", id, lastSelection);
		  final Optional<Merge> action = assignment.getMergeAction(
				  id,
				  lastSelection,
				  idService::next);
		  action.ifPresent(assignment::apply);
		}
	  }
	}

  }

  private class DetachFragment implements Consumer<MouseEvent> {

	private final ViewerPanelFX viewer;

	private DetachFragment(final ViewerPanelFX viewer) {

	  this.viewer = viewer;
	}

	@Override
	public void accept(final MouseEvent e) {

	  final long lastSelection = selectedIds.getLastSelection();

	  if (lastSelection == Label.INVALID) {
		return;
	  }

	  final AffineTransform3D screenScaleTransform = new AffineTransform3D();
	  viewer.getRenderUnit().getScreenScaleTransform(0, screenScaleTransform);
	  final int level = viewer.getState().getBestMipMapLevel(screenScaleTransform, source);

	  final AffineTransform3D affine = new AffineTransform3D();
	  source.getSourceTransform(0, level, affine);
	  final RealRandomAccessible<? extends IntegerType<?>> transformedSource = RealViews
			  .transformReal(
					  source.getInterpolatedDataSource(0, level, Interpolation.NEARESTNEIGHBOR),
					  affine);
	  final RealRandomAccess<? extends IntegerType<?>> access = transformedSource.realRandomAccess();
	  viewer.getMouseCoordinates(access);
	  access.setPosition(0L, 2);
	  viewer.displayToGlobalCoordinates(access);
	  final IntegerType<?> val = access.get();
	  final long id = val.getIntegerLong();

	  if (FOREGROUND_CHECK.test(id)) {
		final Optional<Detach> detach = assignment.getDetachAction(id, lastSelection);
		detach.ifPresent(assignment::apply);
	  }
	}

  }

  private class ConfirmSelection implements Consumer<MouseEvent> {

	private final ViewerPanelFX viewer;

	private ConfirmSelection(final ViewerPanelFX viewer) {

	  this.viewer = viewer;
	}

	@Override
	public void accept(final MouseEvent e) {

	  final long[] activeFragments = selectedIds.getActiveIdsCopyAsArray();
	  final long[] activeSegments = Arrays.stream(activeFragments).map(assignment::getSegment).toArray();

	  if (activeSegments.length > 1) {
		LOG.info("More than one segment active, not doing anything!");
		return;
	  }

	  if (activeSegments.length == 0) {
		LOG.info("No segments active, not doing anything!");
		return;
	  }

	  final AffineTransform3D screenScaleTransform = new AffineTransform3D();
	  viewer.getRenderUnit().getScreenScaleTransform(0, screenScaleTransform);
	  final int level = viewer.getState().getBestMipMapLevel(screenScaleTransform, source);

	  final AffineTransform3D affine = new AffineTransform3D();
	  source.getSourceTransform(0, level, affine);
	  final RealRandomAccessible<? extends IntegerType<?>> transformedSource = RealViews
			  .transformReal(
					  source.getInterpolatedDataSource(0, level, Interpolation.NEARESTNEIGHBOR),
					  affine);
	  final RealRandomAccess<? extends IntegerType<?>> access = transformedSource.realRandomAccess();
	  viewer.getMouseCoordinates(access);
	  access.setPosition(0L, 2);
	  viewer.displayToGlobalCoordinates(access);
	  final IntegerType<?> val = access.get();
	  final long selectedFragment = val.getIntegerLong();
	  final long selectedSegment = assignment.getSegment(selectedFragment);
	  final TLongHashSet selectedSegmentsSet = new TLongHashSet(new long[]{selectedSegment});
	  final TLongHashSet visibleFragmentsSet = new TLongHashSet();

	  if (!FOREGROUND_CHECK.test(selectedFragment))
		return;

	  if (activeSegments[0] == selectedSegment) {
		LOG.debug("confirm merge and separate of single segment");
		VisitEveryDisplayPixel.visitEveryDisplayPixel(
				source,
				viewer,
				obj -> visibleFragmentsSet.add(obj.getIntegerLong()));
		final long[] visibleFragments = visibleFragmentsSet.toArray();
		final long[] fragmentsInActiveSegment = Arrays.stream(visibleFragments).filter(frag -> selectedSegmentsSet.contains(assignment.getSegment(frag)))
				.toArray();
		final long[] fragmentsNotInActiveSegment = Arrays.stream(visibleFragments).filter(frag -> !selectedSegmentsSet.contains(assignment.getSegment(frag)))
				.toArray();

		final Optional<AssignmentAction> action = assignment.getConfirmGroupingAction(fragmentsInActiveSegment, fragmentsNotInActiveSegment);
		action.ifPresent(assignment::apply);
	  } else {
		LOG.debug("confirm merge and separate of two segments");
		final long[] relevantSegments = new long[]{activeSegments[0],
				selectedSegment};
		final TLongObjectHashMap<TLongHashSet> fragmentsBySegment = new TLongObjectHashMap<>();
		Arrays.stream(relevantSegments).forEach(seg -> fragmentsBySegment.put(
				seg,
				new TLongHashSet()));
		VisitEveryDisplayPixel.visitEveryDisplayPixel(source, viewer, obj -> {
		  final long frag = obj.getIntegerLong();
		  final TLongHashSet frags = fragmentsBySegment.get(assignment.getSegment(frag));
		  if (frags != null) {
			frags.add(frag);
		  }
		});
		final Optional<AssignmentAction> action = assignment.getConfirmTwoSegmentsAction(
				fragmentsBySegment.get(relevantSegments[0]).toArray(),
				fragmentsBySegment.get(relevantSegments[1]).toArray());
		action.ifPresent(assignment::apply);
	  }

	}
  }
}
