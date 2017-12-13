package bdv.bigcat.viewer.atlas.opendialog;

import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;

import bdv.bigcat.viewer.atlas.opendialog.meta.MetaPanel;
import bdv.bigcat.viewer.util.InvokeOnJavaFXApplicationThread;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.effect.InnerShadow;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

public class OpenSourceDialog extends Dialog< BackendDialog > implements CombinesErrorMessages
{

	public static Color TEXTFIELD_ERROR = Color.ORANGE;

	public static enum BACKEND
	{
		N5, HDF5, DVID
	};

	public static enum TYPE
	{
		RAW, LABEL
	};

	private final VBox dialogContent;

	private final GridPane grid;

	private final StackPane backendDialog;

	private final ComboBox< BACKEND > backendChoice;

	private final ComboBox< TYPE > typeChoice;

	private final Label errorMessage;

	private final TitledPane errorInfo;

	private final ObservableList< BACKEND > backendChoices = FXCollections.observableArrayList( BACKEND.values() );

	private final ObservableList< TYPE > typeChoices = FXCollections.observableArrayList( TYPE.values() );

	private final SimpleObjectProperty< BackendDialog > currentBackend = new SimpleObjectProperty<>( new BackendDialogInvalid( BACKEND.N5 ) );

	private final NameField nameField = new NameField( "Source name", "Specify source name (required)", new InnerShadow( 10, Color.ORANGE ) );

	private final BooleanBinding isError;

	private final ObservableMap< BACKEND, BackendDialog > backendInfoDialogs = FXCollections.observableHashMap();
	{
		backendInfoDialogs.put( BACKEND.N5, new BackendDialogN5() );
		backendInfoDialogs.put( BACKEND.HDF5, new BackendDialogHDF5() );
	}

	private final MetaPanel metaPanel = new MetaPanel();

	public OpenSourceDialog()
	{
		super();
		this.setTitle( "Open data set" );
		this.getDialogPane().getButtonTypes().addAll( ButtonType.CANCEL, ButtonType.OK );
		this.errorMessage = new Label( "" );
		this.errorInfo = new TitledPane( "", errorMessage );
		this.isError = Bindings.createBooleanBinding( () -> Optional.ofNullable( this.errorMessage.textProperty().get() ).orElse( "" ).length() > 0, this.errorMessage.textProperty() );
		errorInfo.textProperty().bind( Bindings.createStringBinding( () -> this.isError.get() ? "ERROR" : "", this.isError ) );

		this.getDialogPane().lookupButton( ButtonType.OK ).disableProperty().bind( this.isError );
		this.errorInfo.visibleProperty().bind( this.isError );

		this.grid = new GridPane();
		this.backendDialog = new StackPane();
		this.nameField.errorMessageProperty().addListener( ( obs, oldv, newv ) -> combineErrorMessages() );
		this.dialogContent = new VBox( 10, nameField.textField(), grid, metaPanel.getPane(), errorInfo );
		this.setResizable( true );

		GridPane.setMargin( this.backendDialog, new Insets( 0, 0, 0, 30 ) );
		this.grid.add( this.backendDialog, 1, 0 );
		GridPane.setHgrow( this.backendDialog, Priority.ALWAYS );

		this.getDialogPane().setContent( dialogContent );
		final VBox choices = new VBox();
		this.backendChoice = new ComboBox<>( backendChoices );
		this.typeChoice = new ComboBox<>( typeChoices );
		this.metaPanel.bindDataTypeTo( this.typeChoice.valueProperty() );

		this.backendChoice.valueProperty().addListener( ( obs, oldv, newv ) -> {
			if ( this.currentBackend.get() != null )
				Bindings.unbindBidirectional(
						this.currentBackend.get().axisOrder(),
						this.metaPanel.axisOrderProperty() );
			InvokeOnJavaFXApplicationThread.invoke( () -> {
				final BackendDialog backendDialog = Optional.ofNullable( backendInfoDialogs.get( newv ) ).orElse( new BackendDialogInvalid( newv ) );
				this.backendDialog.getChildren().setAll( backendDialog.getDialogNode() );
//				this.errorMessage.bind( backendDialog.errorMessage() );
				this.currentBackend.set( backendDialog );

				this.metaPanel.axisOrderProperty().bindBidirectional( backendDialog.axisOrder() );
				this.metaPanel.defaultAxisOrderProperty().bind( backendDialog.axisOrder() );
				this.metaPanel.listenOnResolution( backendDialog.resolutionX(), backendDialog.resolutionY(), backendDialog.resolutionZ() );
				this.metaPanel.listenOnOffset( backendDialog.offsetX(), backendDialog.offsetY(), backendDialog.offsetZ() );
				this.metaPanel.listenOnMinMax( backendDialog.min(), backendDialog.max() );

				backendDialog.errorMessage().addListener( ( obsErr, oldErr, newErr ) -> combineErrorMessages() );
				combineErrorMessages();
			} );
		} );

		this.backendChoice.setValue( backendChoices.get( 0 ) );
		this.typeChoice.setValue( typeChoices.get( 0 ) );
		this.backendChoice.setMinWidth( 100 );
		this.typeChoice.setMinWidth( 100 );
		choices.getChildren().addAll( this.backendChoice, this.typeChoice );
		this.grid.add( choices, 0, 0 );
		this.setResultConverter( button -> button.equals( ButtonType.OK ) ? currentBackend.get() : new BackendDialogInvalid( backendChoice.getValue() ) );
		combineErrorMessages();

		this.typeChoice.valueProperty().addListener( ( obs, oldv, newv ) -> {
			final BackendDialog backendDialog = backendInfoDialogs.get( backendChoice.getValue() );
			backendDialog.typeChanged( newv );
		} );
	}

	public TYPE getType()
	{
		return typeChoice.getValue();
	}

	public String getName()
	{
		return nameField.getText();
	}

	public MetaPanel getMeta()
	{
		return this.metaPanel;
	}

	@Override
	public Collection< ObservableValue< String > > errorMessages()
	{
		return Arrays.asList( this.nameField.errorMessageProperty(), this.currentBackend.get().errorMessage() );
	}

	@Override
	public Consumer< Collection< String > > combiner()
	{
		return strings -> InvokeOnJavaFXApplicationThread.invoke( () -> this.errorMessage.setText( String.join( "\n", strings ) ) );
	}

}
