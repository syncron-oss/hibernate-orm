package org.hibernate.type;

import org.hibernate.type.descriptor.java.JavaTypeDescriptor;
import org.hibernate.type.descriptor.sql.SqlTypeDescriptor;

/**
 * @author piofin <piotr.findeisen@syncron.com>
 * @since Aug 25, 2015
 */
public abstract class SyncAbstractSingleColumnType<T> extends AbstractSingleColumnStandardBasicType<T> {

	private static final long serialVersionUID = 1L;

	protected SyncAbstractSingleColumnType(
			SqlTypeDescriptor sqlTypeDescriptor,
			/*@Nullable*/ JavaTypeDescriptor<T> javaTypeDescriptor) {
		super( sqlTypeDescriptor, javaTypeDescriptor );
	}

	/**
	 * {@inheritDoc}
	 * <p/>
	 * Must return {@code null} unless the type is registered in {@link BasicTypeRegistry}.
	 */
	@Override
	public String getName() {
		return null;
	}
}
