package org.janelia.saalfeldlab.paintera.ui.opendialog.menu.intersecting;

import bdv.viewer.Source;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.fxmisc.richtext.InlineCssTextArea;
import org.janelia.saalfeldlab.fx.Labels;
import org.janelia.saalfeldlab.fx.ui.Exceptions;
import org.janelia.saalfeldlab.paintera.Paintera;
import org.janelia.saalfeldlab.paintera.PainteraBaseView;
import org.janelia.saalfeldlab.paintera.composition.ARGBCompositeAlphaAdd;
import org.janelia.saalfeldlab.paintera.state.IntersectableSourceState;
import org.janelia.saalfeldlab.paintera.state.IntersectingSourceState;
import org.janelia.saalfeldlab.paintera.state.SourceInfo;
import org.janelia.saalfeldlab.paintera.state.SourceState;
import org.janelia.saalfeldlab.paintera.ui.PainteraAlerts;
import org.janelia.saalfeldlab.util.Colors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class IntersectingSourceStateOpener {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final String TEXT_BACKGROUND = "-rtfx-background-color: rgba(62,62,62,0.62); ";
  private static final String BOLD = "-fx-font-weight: bold; ";
  private static final String CONNECTED_COMPONENT_STYLE = TEXT_BACKGROUND + BOLD + "-fx-fill: #52ffea;";
  private static final String SEED_SOURCE_STYLE = TEXT_BACKGROUND + BOLD + "-fx-fill: #36ff60;";
  private static final String FILL_SOURCE_STYLE = TEXT_BACKGROUND + BOLD + "-fx-fill: #ffdbff;";

  public static void createAndAddVirtualIntersectionSource(final PainteraBaseView viewer, Supplier<String> projectDirectory) {

	final ObjectProperty<IntersectableSourceState<?, ?, ?>> firstSourceStateProperty = new SimpleObjectProperty<>();
	final ObjectProperty<IntersectableSourceState<?, ?, ?>> secondSourceStateProperty = new SimpleObjectProperty<>();
	final StringProperty name = new SimpleStringProperty(null);
	final ObjectProperty<Color> color = new SimpleObjectProperty<>(Color.WHITE);
	final Alert dialog = makeDialog(viewer, firstSourceStateProperty, secondSourceStateProperty, name, color);
	final Optional<ButtonType> returnType = dialog.showAndWait();
	if (Alert.AlertType.CONFIRMATION.equals(dialog.getAlertType()) && ButtonType.OK.equals(returnType.orElse(ButtonType.CANCEL))) {
	  try {
		final IntersectingSourceState<?, ?> intersectingState = new IntersectingSourceState<>(
				secondSourceStateProperty.get(),
				firstSourceStateProperty.get(),
				new ARGBCompositeAlphaAdd(),
				name.get(),
				0,
				viewer
		);

		intersectingState.converter().setColor(Colors.toARGBType(color.get()));
		viewer.addState(intersectingState);
	  } catch (final Exception e) {
		LOG.error("Unable to create intersecting state", e);
		Exceptions.exceptionAlert(Paintera.Constants.NAME, "Unable to create intersecting state", e).show();
	  }
	}
  }

  private static Alert makeDialog(
		  final PainteraBaseView viewer,
		  final ObjectProperty<IntersectableSourceState<?, ?, ?>> sourceStateOne,
		  final ObjectProperty<IntersectableSourceState<?, ?, ?>> sourceStateTwo,
		  final StringProperty name,
		  final ObjectProperty<Color> color) {

	final SourceInfo sourceInfo = viewer.sourceInfo();
	final List<Source<?>> sources = new ArrayList<>(sourceInfo.trackSources());
	final List<SourceState<?, ?>> states = sources.stream().map(sourceInfo::getState).collect(Collectors.toList());
	final List<IntersectableSourceState<?, ?, ?>> sourceStatesOne = states.stream()
			.filter(x -> x instanceof IntersectableSourceState<?, ?, ?>)
			.map(x -> (IntersectableSourceState<?, ?, ?>)x)
			.collect(Collectors.toList());

	final List<IntersectableSourceState<?, ?, ?>> sourceStatesTwo = List.copyOf(sourceStatesOne);

	if (sourceStatesOne.isEmpty()) {
	  final Alert dialog = PainteraAlerts.alert(Alert.AlertType.ERROR, true);
	  dialog.setContentText("No Intersectable sources loaded yet, cannot create connected component state.");
	  return dialog;
	}

	final Alert dialog = PainteraAlerts.alert(Alert.AlertType.CONFIRMATION, true);
	dialog.setHeaderText("Choose sources for intersection and \nconnected component rendering.");

	final Map<SourceState<?, ?>, Integer> sourceIndices = sources
			.stream()
			.collect(Collectors.toMap(sourceInfo::getState, sourceInfo::indexOf));

	final ComboBox<IntersectableSourceState<?, ?, ?>> selectionOne = new ComboBox<>(FXCollections.observableArrayList(sourceStatesOne));
	final ComboBox<IntersectableSourceState<?, ?, ?>> selectionTwo = new ComboBox<>(FXCollections.observableArrayList(sourceStatesTwo));

	sourceStateOne.bind(selectionOne.valueProperty());
	sourceStateTwo.bind(selectionTwo.valueProperty());
	final double idLabelWidth = 20.0;

	selectionOne.setCellFactory(param -> new ListCell<>() {

	  @Override
	  protected void updateItem(IntersectableSourceState<?, ?, ?> item, boolean empty) {

		super.updateItem(item, empty);
		if (item == null || empty) {
		  setGraphic(null);
		} else {
		  final Label id = new Label(String.format("%d:", sourceIndices.get(item)));
		  id.setPrefWidth(idLabelWidth);
		  setGraphic(id);
		  setText(item.nameProperty().get());
		}
	  }
	});

	selectionTwo.setCellFactory(param -> new ListCell<>() {

	  @Override
	  protected void updateItem(IntersectableSourceState<?, ?, ?> item, boolean empty) {

		super.updateItem(item, empty);
		if (item == null || empty) {
		  setGraphic(null);
		} else {
		  final Label id = new Label(Integer.toString(sourceIndices.get(item)) + ":");
		  id.setPrefWidth(idLabelWidth);
		  setGraphic(id);
		  setText(item.nameProperty().get());
		}
	  }
	});

	selectionOne.setButtonCell(selectionOne.getCellFactory().call(null));
	selectionTwo.setButtonCell(selectionTwo.getCellFactory().call(null));
	selectionOne.setMaxWidth(Double.POSITIVE_INFINITY);
	selectionTwo.setMaxWidth(Double.POSITIVE_INFINITY);

	selectionOne.setPromptText("Select seed source");
	selectionTwo.setPromptText("Select filled component source");

	final TextField nameField = new TextField(null);
	nameField.setPromptText("Set name for intersecting source");
	name.bind(nameField.textProperty());

	final ColorPicker colorPicker = new ColorPicker();
	colorPicker.valueProperty().bindBidirectional(color);

	final GridPane grid = new GridPane();

	grid.add(Labels.withTooltip("Seed Source", "Select seed source."), 0, 0);
	grid.add(Labels.withTooltip("Fill Component Source", "Select component source."), 0, 1);
	grid.add(new Label("Name"), 0, 2);
	grid.add(new Label("Color"), 0, 3);

	grid.add(selectionOne, 1, 0);
	grid.add(selectionTwo, 1, 1);
	grid.add(nameField, 1, 2);
	grid.add(colorPicker, 1, 3);

	final var infoPane = PainteraAlerts.alert(Alert.AlertType.INFORMATION, false);
	final var view = new ImageView("PainteraIntersectionInfo.png");
	final var richText = new InlineCssTextArea();

	richText.setEditable(false);
	richText.append("To create a ", "");
	richText.append("connected component source ", CONNECTED_COMPONENT_STYLE);
	richText.append("select a", "");
	richText.append(" seed source ", SEED_SOURCE_STYLE);
	richText.append("and a", "");
	richText.append(" fill source", FILL_SOURCE_STYLE);
	richText.append(".\n\nThe", "");
	richText.append(" seed source ", SEED_SOURCE_STYLE);
	richText.append("is used to detect intersection with the", "");
	richText.append(" fill source", FILL_SOURCE_STYLE);
	richText.append(".\n\nAll components in the", "");
	richText.append(" fill source ", FILL_SOURCE_STYLE);
	richText.append("that overlap with the", "");
	richText.append(" seed source ", SEED_SOURCE_STYLE);
	richText.append("are filled into to create", "");
	richText.append(" connected component source", CONNECTED_COMPONENT_STYLE);
	richText.append(".", "");
	final var vbox = new VBox(view, richText);
	infoPane.getDialogPane().setContent(vbox);

	GridPane.setHgrow(selectionOne, Priority.ALWAYS);
	GridPane.setHgrow(selectionTwo, Priority.ALWAYS);

	dialog.getDialogPane().setContent(grid);

	BooleanProperty okButtonDisabledProp = dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty();
	okButtonDisabledProp.bind(selectionOne
			.valueProperty()
			.isNull()
			.or(selectionTwo.valueProperty().isNull())
			.or(name.isEmpty())
	);

	final ButtonType helpButtonType = new ButtonType("Help", ButtonBar.ButtonData.HELP_2);
	dialog.getDialogPane().getButtonTypes().add(helpButtonType);

	final var helpButton = (Button)dialog.getDialogPane().lookupButton(helpButtonType);
	/* NOTE: I know it seems odd to trigger the help dialog in the event filter, instead of the `onAction`,
	 *	but the issue is that Dialog's automatically close when any button is pressed.
	 * 	To stop the dialog from closing when the HELP button is pressed, we need to intercept and consume the event.
	 * 	Since we need to consume the event though, it means */
	helpButton.addEventFilter(ActionEvent.ACTION, event -> {
	  if (!infoPane.isShowing()) {
		infoPane.show();
	  }
	  event.consume();
	});

	return dialog;
  }

}
