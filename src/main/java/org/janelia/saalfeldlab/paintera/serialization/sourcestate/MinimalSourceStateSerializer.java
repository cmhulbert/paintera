package org.janelia.saalfeldlab.paintera.serialization.sourcestate;

import net.imglib2.converter.Converter;
import net.imglib2.type.numeric.ARGBType;
import org.janelia.saalfeldlab.paintera.serialization.StatefulSerializer;
import org.janelia.saalfeldlab.paintera.state.MinimalSourceState;
import org.janelia.saalfeldlab.paintera.state.SourceState;

import java.util.function.Supplier;
import java.util.function.ToIntFunction;

public class MinimalSourceStateSerializer extends
		SourceStateSerialization
				.SourceStateSerializerWithDependencies<MinimalSourceState<?, ?, ?,
				Converter<?, ARGBType>>> {

	public MinimalSourceStateSerializer(final ToIntFunction<SourceState<?, ?>> stateToIndex) {

		super(stateToIndex);
	}

	public static class Factory implements
			StatefulSerializer.SerializerFactory<MinimalSourceState<?, ?, ?, Converter<?, ARGBType>>,
					MinimalSourceStateSerializer> {

		@Override
		public MinimalSourceStateSerializer createSerializer(
				final Supplier<String> projectDirectory,
				final ToIntFunction<SourceState<?, ?>> stateToIndex) {

			return new MinimalSourceStateSerializer(stateToIndex);
		}

		@Override
		public Class<MinimalSourceState<?, ?, ?, Converter<?, ARGBType>>> getTargetClass() {

			return (Class<MinimalSourceState<?, ?, ?, Converter<?, ARGBType>>>)(Class<?>)MinimalSourceState.class;
		}
	}

}
