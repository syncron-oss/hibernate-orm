package org.hibernate.type.descriptor.java;

import java.math.BigDecimal;
import java.math.RoundingMode;

import javax.persistence.Column;

import org.hibernate.type.descriptor.java.BigDecimalTypeDescriptor;
import org.hibernate.type.descriptor.java.JavaTypeDescriptor;

/**
 * A {@link JavaTypeDescriptor} for {@link BigDecimal} that scales (rounds) values before comparing. The target scale is
 * taken from {@link Column} meta-data, thus values are compared equal if and only if they have equal representation in
 * the database.
 * <p/>
 * <em>Warning:</em> this class utilizes {@link Column} meta-data, so it does the right thing only when the meta-data is
 * correct.
 *
 * @author piofin <piotr.findeisen@syncron.com>
 * @see BigDecimalTypeDescriptor Hibernate default type descriptor for {@link BigDecimal}
 * @since Dec 1, 2016
 */
public class BigDecimalRoundingJavaTypeDescriptor extends BigDecimalTypeDescriptor {
	private static final long serialVersionUID = 1L;

	private final int scale;

	public BigDecimalRoundingJavaTypeDescriptor(int scale) {
		this.scale = scale;
	}

	@Override
	public boolean areEqual(BigDecimal one, BigDecimal another) {
		if ( one == null || another == null ) {
			return one == another;
		}
		if ( one.compareTo( another ) == 0 ) {
			return true;
		}

		if ( one.scale() <= scale && another.scale() <= scale ) {
			return false;
		}

		one = roundToScale( one, scale );
		another = roundToScale( another, scale );

		return one.compareTo( another ) == 0;
	}

	private static BigDecimal roundToScale(BigDecimal bd, int maximumScale) {
		// We must follow database rounding behavior here.
		if ( bd.scale() <= maximumScale ) {
			return bd;
		}
		else {
			return bd.setScale( maximumScale, RoundingMode.HALF_UP );
		}
	}

}
