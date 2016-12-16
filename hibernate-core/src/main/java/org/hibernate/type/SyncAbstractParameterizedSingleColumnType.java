package org.hibernate.type;

import java.util.Properties;

import org.hibernate.type.descriptor.java.JavaTypeDescriptor;
import org.hibernate.type.descriptor.sql.BooleanTypeDescriptor;
import org.hibernate.type.descriptor.sql.SqlTypeDescriptor;
import org.hibernate.usertype.ParameterizedType;

/**
 * @author piofin <piotr.findeisen@syncron.com>
 * @since Aug 28, 2015
 */
public abstract class SyncAbstractParameterizedSingleColumnType<T>
		extends SyncAbstractSingleColumnType<T>
		implements ParameterizedType {

	private static final long serialVersionUID = 1L;

	public SyncAbstractParameterizedSingleColumnType() {
		super(
				BooleanTypeDescriptor.INSTANCE, // fake, re-set in setParameterValues
				(JavaTypeDescriptor<T>) null // set in setParameterValues
		);
	}

	@Override
	public final void setParameterValues(Properties parameters) {
		doSetParameterValues( parameters );

		SqlTypeDescriptor sqlTypeDescriptor = makeSqlTypeDescriptor();
		setSqlTypeDescriptor( sqlTypeDescriptor );

		JavaTypeDescriptor<T> javaTypeDescriptor = makeJavaTypeDescriptor();
		setJavaTypeDescriptor( javaTypeDescriptor );
	}

	protected abstract void doSetParameterValues(Properties parameters);

	protected abstract SqlTypeDescriptor makeSqlTypeDescriptor();

	protected abstract JavaTypeDescriptor<T> makeJavaTypeDescriptor();

}
