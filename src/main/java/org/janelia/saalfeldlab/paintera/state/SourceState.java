package org.janelia.saalfeldlab.paintera.state;

import java.util.function.LongFunction;

import org.janelia.saalfeldlab.paintera.composition.Composite;
import org.janelia.saalfeldlab.paintera.control.assignment.FragmentSegmentAssignmentState;
import org.janelia.saalfeldlab.paintera.control.selection.SelectedIds;
import org.janelia.saalfeldlab.paintera.data.DataSource;
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource;
import org.janelia.saalfeldlab.paintera.id.IdService;
import org.janelia.saalfeldlab.paintera.id.ToIdConverter;
import org.janelia.saalfeldlab.paintera.meshes.InterruptibleFunction;
import org.janelia.saalfeldlab.paintera.meshes.MeshGenerator.ShapeKey;
import org.janelia.saalfeldlab.paintera.meshes.MeshInfos;
import org.janelia.saalfeldlab.paintera.meshes.MeshManager;
import org.janelia.saalfeldlab.paintera.stream.ARGBStream;

import bdv.viewer.Interpolation;
import bdv.viewer.SourceAndConverter;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableBooleanValue;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.type.Type;
import net.imglib2.type.logic.BoolType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.util.Pair;

public class SourceState< T extends Type< T >, D extends Type< D > >
{

	public enum TYPE
	{
		RAW, LABEL, GENERIC
	};

	public SourceState(
			final DataSource< D, T > dataSource,
			final Converter< T, ARGBType > converter,
			final Composite< ARGBType, ARGBType > composite,
			final TYPE type )
	{
		this.dataSource.set( dataSource );
		this.converter.set( converter );
		this.composite.set( composite );
		this.visible.set( true );
		this.type.set( type );
		this.name.set( dataSource.getName() );
	}

	private final BooleanProperty stateChanged = new SimpleBooleanProperty();
	{
		stateChanged.addListener( ( obs, oldv, newv ) -> stateChanged.set( false ) );
	}

	private final ObjectProperty< DataSource< D, T > > dataSource = stateChangingObjectProperty( stateChanged );

	private final ObjectProperty< Converter< T, ARGBType > > converter = stateChangingObjectProperty( stateChanged );

	private final ObjectProperty< Composite< ARGBType, ARGBType > > composite = stateChangingObjectProperty( stateChanged );

	private final BooleanProperty visible = stateChangingBooleanProperty( stateChanged );

	private final ObjectProperty< TYPE > type = stateChangingObjectProperty( stateChanged );

	private final ObjectProperty< LongFunction< Converter< D, BoolType > > > maskGenerator = stateChangingObjectProperty( stateChanged );

	private final ObjectProperty< MaskedSource< ?, ? > > maskedSource = stateChangingObjectProperty( stateChanged );

	private final ObjectProperty< FragmentSegmentAssignmentState > assignment = stateChangingObjectProperty( stateChanged );

	private final ObjectProperty< ToIdConverter > toIdConverter = stateChangingObjectProperty( stateChanged );

	private final ObjectProperty< ARGBStream > stream = stateChangingObjectProperty( stateChanged );

	private final ObjectProperty< SelectedIds > selectedIds = stateChangingObjectProperty( stateChanged );

	private final ObjectProperty< IdService > idService = stateChangingObjectProperty( stateChanged );

	private final DoubleProperty selectionMin = stateChangingDoubleProperty( stateChanged );

	private final DoubleProperty selectionMax = stateChangingDoubleProperty( stateChanged );

	private final StringProperty name = stateChangingStringProperty( stateChanged );

	private final ObjectProperty< InterruptibleFunction< Long, Interval[] >[] > blockListCache = stateChangingObjectProperty( stateChanged );

	private final ObjectProperty< InterruptibleFunction< ShapeKey, Pair< float[], float[] > >[] > meshesCache = stateChangingObjectProperty( stateChanged );

	private final ObjectProperty< MeshManager > meshManager = stateChangingObjectProperty( stateChanged );

	private final ObjectProperty< MeshInfos > meshInfos = stateChangingObjectProperty( stateChanged );

	private final ObjectProperty< Interpolation > interpolation = stateChangingObjectProperty( stateChanged, Interpolation.NEARESTNEIGHBOR );

	public ObservableBooleanValue stateChanged()
	{
		return this.stateChanged;
	}

	public SourceAndConverter< T > getSourceAndConverter()
	{
		return new SourceAndConverter<>( dataSource.get(), converter.get() );
	}

	public ReadOnlyObjectProperty< Converter< T, ARGBType > > converterProperty()
	{
		return this.converter;
	}

	public ObjectProperty< Composite< ARGBType, ARGBType > > compositeProperty()
	{
		return this.composite;
	}

	public BooleanProperty visibleProperty()
	{
		return this.visible;
	}

	public ReadOnlyObjectProperty< DataSource< D, T > > dataSourceProperty()
	{
		return this.dataSource;
	}

	public ObjectProperty< LongFunction< Converter< D, BoolType > > > maskGeneratorProperty()
	{
		return this.maskGenerator;
	}

	public ObjectProperty< TYPE > typeProperty()
	{
		return this.type;
	}

	public ObjectProperty< MaskedSource< ?, ? > > maskedSourceProperty()
	{
		return this.maskedSource;
	}

	public ObjectProperty< Interpolation > interpolationProperty()
	{
		return this.interpolation;
	}

	public RandomAccessibleInterval< UnsignedLongType > getUnsignedLongSource( final int t, final int level )
	{
		final ToIdConverter toIdConverter = toIdConverterProperty().get();

		if ( toIdConverter == null ) { return null; }

		final DataSource< D, T > dataSource = dataSourceProperty().get();

		if ( dataSource == null ) { return null; }

		return Converters.convert(
				dataSource.getDataSource( t, level ),
				( source, target ) -> target.set( toIdConverter.biggestFragment( source ) ),
				new UnsignedLongType() );

	}

	public ObjectProperty< FragmentSegmentAssignmentState > assignmentProperty()
	{
		return this.assignment;
	}

	public ObjectProperty< ToIdConverter > toIdConverterProperty()
	{
		return this.toIdConverter;
	}

	public ObjectProperty< ARGBStream > streamProperty()
	{
		return this.stream;
	}

	public ObjectProperty< SelectedIds > selectedIdsProperty()
	{
		return this.selectedIds;
	}

	public ObjectProperty< IdService > idServiceProperty()
	{
		return this.idService;
	}

	public DoubleProperty selectionMinProperty()
	{
		return this.selectionMin;
	}

	public DoubleProperty selectionMaxProperty()
	{
		return this.selectionMax;
	}

	public ObjectProperty< InterruptibleFunction< Long, Interval[] >[] > blocklistCacheProperty()
	{
		return this.blockListCache;
	}

	public StringProperty nameProperty()
	{
		return this.name;
	}

	public ObjectProperty< InterruptibleFunction< ShapeKey, Pair< float[], float[] > >[] > meshesCacheProperty()
	{
		return this.meshesCache;
	}

	public ObjectProperty< MeshManager > meshManagerProperty()
	{
		return this.meshManager;
	}

	public ObjectProperty< MeshInfos > meshInfosProperty()
	{
		return this.meshInfos;
	}

	private static < T > ObjectProperty< T > stateChangingObjectProperty( final BooleanProperty stateChanged )
	{
		return stateChangingObjectProperty( stateChanged, null );
	}

	private static < T > ObjectProperty< T > stateChangingObjectProperty( final BooleanProperty stateChanged, final T initialValue )
	{
		final ObjectProperty< T > property = new SimpleObjectProperty<>( initialValue );
		property.addListener( ( obs, oldv, newv ) -> stateChanged.set( true ) );
		return property;
	}

	private static DoubleProperty stateChangingDoubleProperty( final BooleanProperty stateChanged )
	{
		return stateChangingDoubleProperty( stateChanged, Double.NaN );
	}

	private static DoubleProperty stateChangingDoubleProperty( final BooleanProperty stateChanged, final double value )
	{
		final DoubleProperty property = new SimpleDoubleProperty();
		property.addListener( ( obs, oldv, newv ) -> stateChanged.set( true ) );
		return property;
	}

	private static BooleanProperty stateChangingBooleanProperty( final BooleanProperty stateChanged )
	{
		final SimpleBooleanProperty property = new SimpleBooleanProperty();
		property.addListener( ( obs, oldv, newv ) -> stateChanged.set( true ) );
		return property;
	}

	private static StringProperty stateChangingStringProperty( final BooleanProperty stateChanged )
	{
		final SimpleStringProperty property = new SimpleStringProperty();
		property.addListener( ( obs, oldv, newv ) -> stateChanged.set( true ) );
		return property;
	}

}