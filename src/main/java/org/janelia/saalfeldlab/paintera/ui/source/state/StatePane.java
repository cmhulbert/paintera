package org.janelia.saalfeldlab.paintera.ui.source.state;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Consumer;

import org.janelia.saalfeldlab.paintera.state.SourceState;
import org.janelia.saalfeldlab.paintera.state.LabelSourceState;
import org.janelia.saalfeldlab.paintera.state.SourceInfo;
import org.janelia.saalfeldlab.paintera.ui.BindUnbindAndNodeSupplier;
import org.janelia.saalfeldlab.paintera.ui.CloseButton;
import org.janelia.saalfeldlab.paintera.ui.source.composite.CompositePane;
import org.janelia.saalfeldlab.paintera.ui.source.converter.ConverterPane;
import org.janelia.saalfeldlab.paintera.ui.source.mesh.MeshPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.viewer.Source;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableDoubleValue;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class StatePane implements BindUnbindAndNodeSupplier
{

	private static final Logger LOG = LoggerFactory.getLogger( MethodHandles.lookup().lookupClass() );

	private final SourceState< ?, ? > state;

	private final SourceInfo sourceInfo;

	private final BindUnbindAndNodeSupplier[] children;

	private final TitledPane statePane;

	private final StringProperty name = new SimpleStringProperty();

	private final BooleanProperty isCurrentSource = new SimpleBooleanProperty();

	private final BooleanProperty isVisible = new SimpleBooleanProperty();

	public StatePane(
			final SourceState< ?, ? > state,
			final SourceInfo sourceInfo,
			final Consumer< Source< ? > > remove,
			final ObservableDoubleValue width )
	{
		super();
		this.state = state;
		this.sourceInfo = sourceInfo;
		this.children = new BindUnbindAndNodeSupplier[] {
				new CompositePane( state.compositeProperty() ),
				new ConverterPane( state.getConverter() ),
				state instanceof LabelSourceState< ?, ? > ? meshPane( ( LabelSourceState< ?, ? > ) state ) : BindUnbindAndNodeSupplier.empty()
		};

		final VBox contents = new VBox( Arrays.stream( this.children ).map( c -> c.get() ).toArray( Node[]::new ) );
		this.statePane = new TitledPane( null, contents );
		this.statePane.minWidthProperty().bind( width );
		this.statePane.maxWidthProperty().bind( width );
		this.statePane.prefWidthProperty().bind( width );
		this.statePane.setExpanded( false );

		// create graphics for titled pane
		final Node closeButton = CloseButton.create( 8 );
		closeButton.setOnMousePressed( event -> remove.accept( state.getDataSource() ) );
		final Label sourceElementLabel = new Label( state.nameProperty().get(), closeButton );
		sourceElementLabel.textProperty().bind( this.name );
		sourceElementLabel.setOnMouseClicked( event -> {
			event.consume();
			if ( event.getClickCount() != 2 )
				return;
			final Dialog< Boolean > d = new Dialog<>();
			d.setTitle( "Set source name" );
			final TextField tf = new TextField( name.get() );
			tf.setPromptText( "source name" );
			d.getDialogPane().getButtonTypes().addAll( ButtonType.OK, ButtonType.CANCEL );
			d.getDialogPane().lookupButton( ButtonType.OK ).disableProperty().bind( tf.textProperty().isNull().or( tf.textProperty().length().isEqualTo( 0 ) ) );
			d.setGraphic( tf );
			d.setResultConverter( ButtonType.OK::equals );
			final Optional< Boolean > result = d.showAndWait();
			if ( result.isPresent() && result.get() )
				name.set( tf.getText() );
		} );
		sourceElementLabel.setContentDisplay( ContentDisplay.RIGHT );
		sourceElementLabel.underlineProperty().bind( isCurrentSource );

		final HBox sourceElementButtons = getPaneGraphics( isVisible );
		sourceElementButtons.setMaxWidth( Double.MAX_VALUE );
		HBox.setHgrow( sourceElementButtons, Priority.ALWAYS );
		final HBox graphic = new HBox( sourceElementButtons, sourceElementLabel );
		graphic.setSpacing( 20 );
//		graphic.prefWidthProperty().bind( this.width.multiply( 0.8 ) );
		this.statePane.setGraphic( graphic );
//		addDragAndDropListener( statePane, this.info, contents.getChildren() );
	}

	@Override
	public Node get()
	{
		return this.statePane;
	}

	@Override
	public void bind()
	{
		this.name.bindBidirectional( state.nameProperty() );
		this.isVisible.bindBidirectional( state.isVisibleProperty() );
		this.isCurrentSource.bind( sourceInfo.isCurrentSource( state.getDataSource() ) );
		Arrays.stream( children ).forEach( BindUnbindAndNodeSupplier::bind );
	}

	@Override
	public void unbind()
	{
		this.name.unbindBidirectional( state.nameProperty() );
		this.isVisible.unbindBidirectional( state.isVisibleProperty() );
		this.isCurrentSource.unbind();
		Arrays.stream( children ).forEach( BindUnbindAndNodeSupplier::unbind );
	}

	private static HBox getPaneGraphics( final BooleanProperty isVisible )
	{
		final CheckBox cb = new CheckBox();
		cb.setMaxWidth( 20 );
		cb.selectedProperty().bindBidirectional( isVisible );
		cb.selectedProperty().set( isVisible.get() );
		final HBox tp = new HBox( cb );
		return tp;
	}

	private static BindUnbindAndNodeSupplier meshPane( final LabelSourceState< ?, ? > state )
	{
		LOG.debug( "Creating mesh pane for source {} from {} and {}: ", state.nameProperty().get(), state.meshManager(), state.meshInfos() );
		if ( state.meshManager() != null && state.meshInfos() != null )
			return new MeshPane(
					state.meshManager(),
					state.meshInfos(),
					state.getDataSource().getNumMipmapLevels() );
		return BindUnbindAndNodeSupplier.empty();
	}

//	private static void addDragAndDropListener( final Node p, final SourceInfo info, final List< Node > children )
//	{
//		p.setOnDragDetected( event -> {
//			p.startFullDrag();
//		} );
//
//		p.setOnMouseDragReleased( event -> {
//			final Object origin = event.getGestureSource();
//			if ( origin != p && origin instanceof TitledPane )
//			{
//				final TitledPane pane = ( TitledPane ) origin;
//				final int sourceIndex = children.indexOf( pane );
//				final int targetIndex = children.indexOf( p );
//				info.moveSourceTo( sourceIndex, targetIndex );
//			}
//		} );
//	}

}
