package org.janelia.saalfeldlab.paintera.state;

import bdv.fx.viewer.ViewerPanelFX;
import javafx.concurrent.Task;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.scene.Cursor;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import net.imglib2.type.label.Label;
import net.imglib2.type.numeric.IntegerType;
import org.janelia.saalfeldlab.fx.Tasks;
import org.janelia.saalfeldlab.fx.actions.ActionSet;
import org.janelia.saalfeldlab.fx.actions.NamedKeyCombination;
import org.janelia.saalfeldlab.fx.actions.PainteraActionSet;
import org.janelia.saalfeldlab.fx.event.KeyTracker;
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys;
import org.janelia.saalfeldlab.paintera.Paintera;
import org.janelia.saalfeldlab.paintera.control.IdSelector;
import org.janelia.saalfeldlab.paintera.control.actions.LabelActionType;
import org.janelia.saalfeldlab.paintera.control.assignment.FragmentSegmentAssignment;
import org.janelia.saalfeldlab.paintera.control.lock.LockedSegments;
import org.janelia.saalfeldlab.paintera.control.paint.SelectNextId;
import org.janelia.saalfeldlab.paintera.control.selection.SelectedIds;
import org.janelia.saalfeldlab.paintera.data.DataSource;
import org.janelia.saalfeldlab.paintera.id.IdService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

import static javafx.scene.input.KeyEvent.KEY_PRESSED;

public class LabelSourceStateIdSelectorHandler {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final LongPredicate FOREGROUND_CHECK = Label::isForeground;

  private final DataSource<? extends IntegerType<?>, ?> source;

  private final IdService idService;

  private final SelectedIds selectedIds;

  private final FragmentSegmentAssignment assignment;

  private final LockedSegments lockedSegments;

  private final HashMap<ViewerPanelFX, EventHandler<Event>> handlers = new HashMap<>();

  private Task<?> selectAllTask;

  private SelectNextId nextId;

  public LabelSourceStateIdSelectorHandler(
		  final DataSource<? extends IntegerType<?>, ?> source,
		  final IdService idService,
		  final SelectedIds selectedIds,
		  final FragmentSegmentAssignment assignment,
		  final LockedSegments lockedSegments) {

	this.source = source;
	this.idService = idService;
	this.selectedIds = selectedIds;
	this.assignment = assignment;
	this.lockedSegments = lockedSegments;
  }

  public List<ActionSet> makeActionSets(NamedKeyCombination.CombinationMap keyBindings, KeyTracker keyTracker, Supplier<ViewerPanelFX> getActiveViewer) {

	final IdSelector selector = new IdSelector(source, selectedIds, getActiveViewer, FOREGROUND_CHECK);

	final var toggleLabelActions = new PainteraActionSet(LabelActionType.Toggle, "toggle single id", actionSet -> {
	  final var selectMaxCount = selector.selectFragmentWithMaximumCountAction();
	  selectMaxCount.keysDown();
	  selectMaxCount.verify(mouseEvent -> !Paintera.getPaintera().getMouseTracker().isDragging());
	  selectMaxCount.verify(mouseEvent -> mouseEvent.getButton() == MouseButton.PRIMARY);
	  actionSet.addAction(selectMaxCount);
	});

	final var appendLabelActions = new PainteraActionSet(LabelActionType.Append, "append id", actionSet -> {
	  final var appendMaxCount = selector.appendFragmentWithMaximumCountAction();
	  appendMaxCount.verify(mouseEvent -> !Paintera.getPaintera().getMouseTracker().isDragging());
	  appendMaxCount.verify(mouseEvent -> {
		final var button = mouseEvent.getButton();
		final var leftClickTrigger = button == MouseButton.PRIMARY && keyTracker.areOnlyTheseKeysDown(KeyCode.CONTROL);
		final var rightClickTrigger = button == MouseButton.SECONDARY && keyTracker.noKeysActive();
		return leftClickTrigger || rightClickTrigger;
	  });
	  actionSet.addAction(appendMaxCount);
	});

	final var selectAllActions = new PainteraActionSet(LabelActionType.SelectAll, "Select All", actionSet -> {
	  actionSet.addKeyAction(KEY_PRESSED, keyAction -> {
		keyAction.keyMatchesBinding(keyBindings, LabelSourceStateKeys.SELECT_ALL);
		keyAction.verify(event -> selectAllTask == null);
		keyAction.onAction(keyEvent -> Tasks.createTask(task -> {
				  Paintera.getPaintera().getBaseView().getPane().getScene().setCursor(Cursor.WAIT);
				  selectAllTask = task;
				  selector.selectAll();
				  return null;
				}
		).onEnd(objectUtilityTask -> {
				  selectAllTask = null;
				  Paintera.getPaintera().getBaseView().getPane().getScene().setCursor(Cursor.DEFAULT);
				}
		).submit());
	  });
	  actionSet.addKeyAction(KEY_PRESSED, keyAction -> {
		keyAction.keyMatchesBinding(keyBindings, LabelSourceStateKeys.SELECT_ALL_IN_CURRENT_VIEW);
		keyAction.verify(event -> selectAllTask == null);
		keyAction.verify(event -> getActiveViewer.get() != null);
		keyAction.onAction(keyEvent -> Tasks.createTask(task -> {
				  Paintera.getPaintera().getBaseView().getPane().getScene().setCursor(Cursor.WAIT);
				  selectAllTask = task;
				  selector.selectAllInCurrentView(getActiveViewer.get());
				  return null;
				}
		).onEnd(objectUtilityTask -> {
				  selectAllTask = null;
				  Paintera.getPaintera().getBaseView().getPane().getScene().setCursor(Cursor.DEFAULT);
				}
		).submit());
	  });
	  actionSet.addKeyAction(KEY_PRESSED, keyAction -> {
		keyAction.keysDown(KeyCode.ESCAPE);
		keyAction.onAction(keyEvent -> Optional.ofNullable(selectAllTask).ifPresent(Task::cancel));
	  });
	});
	final var lockSegmentActions = new PainteraActionSet(LabelActionType.Lock, "Toggle Segment Lock", actionSet -> {
	  actionSet.addKeyAction(KEY_PRESSED, keyAction -> {
		keyAction.keyMatchesBinding(keyBindings, LabelSourceStateKeys.LOCK_SEGEMENT);
		keyAction.onAction(keyEvent -> selector.toggleLock(assignment, lockedSegments));
	  });
	});

	nextId = new SelectNextId(idService, selectedIds);
	final var createNewActions = new PainteraActionSet(LabelActionType.CreateNew, "Create New Segment", actionSet -> {
	  actionSet.addKeyAction(KEY_PRESSED, keyAction -> {
		keyAction.keyMatchesBinding(keyBindings, LabelSourceStateKeys.NEXT_ID);
		keyAction.onAction(keyEvent -> nextId.getNextId());
	  });
	});

	return List.of(toggleLabelActions, appendLabelActions, selectAllActions, lockSegmentActions, createNewActions);
  }

  public long nextId() {

	return nextId(true);
  }

  public long nextId(boolean activate) {

	if (activate) {
	  return nextId.getNextId();
	} else {
	  /* No-op with the id. */
	  return nextId.getNextId((ids, id) -> {
	  });
	}
  }
}
