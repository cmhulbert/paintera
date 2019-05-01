package org.janelia.saalfeldlab.paintera.data.mask;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

import net.imglib2.type.numeric.IntegerType;
import net.imglib2.util.Triple;
import org.janelia.saalfeldlab.paintera.data.mask.RealPickOne.RealPickAndConvert;

public class RealPickOneAllIntegerTypes<I extends IntegerType<I>, M extends IntegerType<M>>
		implements RealPickOne.RealPickAndConvert<I, M, M, I>
{

	private final Predicate<M> pickThird;

	private final BiPredicate<M, M> pickSecond;

	private final I i;

	public RealPickOneAllIntegerTypes(final Predicate<M> pickThird, final BiPredicate<M, M> pickSecond, final I i)
	{
		super();
		this.pickThird = pickThird;
		this.pickSecond = pickSecond;
		this.i = i;
	}

	@Override
	public I apply(final Triple<I, M, M> t)
	{
		final I a = t.getA();
		final M b = t.getB();
		final M c = t.getC();
		i.setInteger(pickThird.test(c)
		             ? c.getIntegerLong()
		             : pickSecond.test(b, c) ? b.getIntegerLong() : a.getIntegerLong());
		return i;
	}

	@Override
	public RealPickAndConvert<I, M, M, I> copy()
	{
		return new RealPickOneAllIntegerTypes<>(pickThird, pickSecond, i.copy());
	}

}
