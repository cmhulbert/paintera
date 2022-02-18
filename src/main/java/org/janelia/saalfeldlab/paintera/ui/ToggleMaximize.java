package org.janelia.saalfeldlab.paintera.ui;

import javafx.scene.Node;
import org.janelia.saalfeldlab.fx.ortho.GridConstraintsManager;
import org.janelia.saalfeldlab.fx.ortho.GridConstraintsManager.MaximizedColumn;
import org.janelia.saalfeldlab.fx.ortho.GridConstraintsManager.MaximizedRow;
import org.janelia.saalfeldlab.fx.ortho.OrthogonalViews;
import org.janelia.saalfeldlab.fx.ortho.ResizableGridPane2x2;

public class ToggleMaximize {

  private final OrthogonalViews<? extends Node> orthogonalViews;
  private final GridConstraintsManager manager;

  private final MaximizedColumn col;
  private final MaximizedRow row;

  public ToggleMaximize(
		  final OrthogonalViews<? extends Node> orthogonalViews,
		  final GridConstraintsManager manager,
		  final MaximizedColumn col,
		  final MaximizedRow row) {

	this.orthogonalViews = orthogonalViews;
	this.manager = manager;
	this.col = col;
	this.row = row;
  }

  public void toggleMaximizeViewer() {

	boolean bottomIsMax = manager.getMaximizedColumn() == MaximizedColumn.NONE && manager.getMaximizedRow() == MaximizedRow.BOTTOM;
	if (bottomIsMax)
	  toggleMaximizeViewerAndOrthoslice();
	else
	  manager.maximize(row, col, 8);
  }

  public void toggleMaximizeViewerAndOrthoslice() {

	boolean anyCellIsMax = manager.getMaximizedColumn() != MaximizedColumn.NONE && manager.getMaximizedRow() != MaximizedRow.NONE;
	if (anyCellIsMax) {
	  toggleMaximizeViewer();
	  return;
	}

	/* before we change, make sure it's 2 first (which is the proper bottom left index, when nothing is swapper) */

	orthogonalViews.getBottomLeftViewIndexProperty().set(2);
	if (col != MaximizedColumn.LEFT || row != MaximizedRow.BOTTOM) {
	  int colIdx1 = col.getIndex();
	  int rowIdx1 = row.getIndex();
	  final var cellIdx = ResizableGridPane2x2.getCellIndex(colIdx1, rowIdx1);
	  orthogonalViews.getBottomLeftViewIndexProperty().set(cellIdx);
	}
	manager.maximize(MaximizedRow.BOTTOM, 0);

  }

  public void detachViewer() {

	//	InvokeOnJavaFXApplicationThread.invoke(() -> {
	//	  final var viewer = orthogonalViews.getTopLeft().viewer();
	//	  final ResizableGridPane2x2 parent = (ResizableGridPane2x2)viewer.getParent();
	//	  if (parent != null) {
	//		parent.getChildren().remove(viewer);
	//		//		final var test = new ResizableGridPane2x2<>(viewer, viewer, viewer, viewer);
	//		final var stage = new Stage();
	//		final var scene = new Scene(viewer, viewer.getWidth(), viewer.getHeight());
	//		stage.setScene(scene);
	//		stage.show();
	//	  }
	//	});
  }
}
