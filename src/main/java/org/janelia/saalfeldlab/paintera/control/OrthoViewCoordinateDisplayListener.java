package org.janelia.saalfeldlab.paintera.control;

import bdv.fx.viewer.ViewerPanelFX;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import net.imglib2.RealPoint;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.ui.TransformListener;
import org.janelia.saalfeldlab.fx.event.EventFX;
import org.janelia.saalfeldlab.fx.event.InstallAndRemove;
import org.janelia.saalfeldlab.fx.ortho.OrthogonalViews;
import org.janelia.saalfeldlab.paintera.control.navigation.CoordinateDisplayListener;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public class OrthoViewCoordinateDisplayListener {

  private final Map<ViewerPanelFX, InstallAndRemove<Node>> listeners = new HashMap<>();

  private final Map<ViewerPanelFX, TransformListener<AffineTransform3D>> transformListeners = new HashMap<>();

  private final Consumer<RealPoint> submitViewerCoordinate;

  private final Consumer<RealPoint> submitWorldCoordinate;

  private final ObjectProperty<OrthogonalViews.ViewerAndTransforms> activeViewerProperty = new SimpleObjectProperty<>();

  public OrthoViewCoordinateDisplayListener(
		  final Consumer<RealPoint> submitViewerCoordinate,
		  final Consumer<RealPoint> submitWorldCoordinate) {

	super();
	this.submitViewerCoordinate = submitViewerCoordinate;
	this.submitWorldCoordinate = submitWorldCoordinate;
	activeViewerProperty.addListener((obs, exiting, entering) -> {
	  if (Objects.nonNull(exiting)) {
		removeHandlers(exiting.viewer());
	  }
	  if (Objects.nonNull(entering)) {
		addHandlers(entering.viewer());
	  }
	});
  }

  public void addHandlers(ViewerPanelFX viewer) {

	if (!this.listeners.containsKey(viewer)) {
	  final CoordinateDisplayListener coordinateListener =
			  new CoordinateDisplayListener(viewer, submitViewerCoordinate, submitWorldCoordinate);
	  listeners.put(viewer, EventFX.MOUSE_MOVED("coordinate update", e -> coordinateListener.update(e.getX(), e.getY())));
	  final TransformListener<AffineTransform3D> transformListener = transform -> {
		final double[] mouseCoordinates = new double[]{viewer.getMouseXProperty().get(), viewer.getMouseYProperty().get(), 0.0};
		submitViewerCoordinate.accept(new RealPoint(mouseCoordinates));
		viewer.displayToGlobalCoordinates(mouseCoordinates);
		submitWorldCoordinate.accept(new RealPoint(mouseCoordinates));
	  };
	  this.transformListeners.put(viewer, transformListener);
	}
	listeners.get(viewer).installInto(viewer);
	viewer.addTransformListener(transformListeners.get(viewer));
  }

  public void removeHandlers(ViewerPanelFX viewer) {

	listeners.get(viewer).removeFrom(viewer);
	viewer.removeTransformListener(transformListeners.get(viewer));
	submitViewerCoordinate.accept(null);
	submitWorldCoordinate.accept(null);
  }

  public void bindActiveViewer(ObservableValue<OrthogonalViews.ViewerAndTransforms> activeViewerObservable) {
	/* Binding would be neater here, but inexplicably, doesn't work? */
	activeViewerObservable.addListener((obs, oldv, newv) -> activeViewerProperty.set(newv));
  }

}
