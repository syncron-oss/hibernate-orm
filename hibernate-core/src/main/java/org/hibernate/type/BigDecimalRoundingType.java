package org.hibernate.type;

import static java.lang.String.format;

import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.util.Properties;

import javax.persistence.Column;

import org.hibernate.type.descriptor.java.BigDecimalRoundingJavaTypeDescriptor;
import org.hibernate.type.descriptor.java.JavaTypeDescriptor;
import org.hibernate.type.descriptor.sql.SqlTypeDescriptor;
import org.hibernate.usertype.DynamicParameterizedType;

/**
 * A {@link org.hibernate.type.Type} for {@link BigDecimal} using {@link BigDecimalRoundingJavaTypeDescriptor} for its
 * Java-facing aspects. See that class for details.
 *
 * @author piofin <piotr.findeisen@syncron.com>
 * @see org.hibernate.type.BigDecimalType
 * @see BigDecimalRoundingJavaTypeDescriptor
 * @since Dec 1, 2016
 */
public class BigDecimalRoundingType
		extends SyncAbstractParameterizedSingleColumnType<BigDecimal>
		implements DynamicParameterizedType {

	private static final long serialVersionUID = 1L;

	public static final String CLASS_NAME = "org.hibernate.type.BigDecimalRoundingType";

	private Integer scale;

	public BigDecimalRoundingType() {
	}

	@Override
	protected void doSetParameterValues(Properties parameters) {

		ParameterType meta = (ParameterType) parameters.get( DynamicParameterizedType.PARAMETER_TYPE );
		if ( meta == null ) {
			throw new IllegalStateException( "No ParameterType found" );
		}

		Column columnAnnotation = getColumnAnnotation( parameters, meta );

		/*
		 * In org.hibernate.cfg.Ejb3Column.initMappingColumn, scale is taken from annotation only when precision is set
		 * (and positive).
		 */
		scale = columnAnnotation.precision() > 0
				? columnAnnotation.scale()
				: org.hibernate.mapping.Column.DEFAULT_SCALE;
	}

	private Column getColumnAnnotation(Properties parameters, ParameterType meta) {
		for ( Annotation annotation : meta.getAnnotationsMethod() ) {
			if ( annotation instanceof Column ) {
				return (Column) annotation;
			}
		}
		throw new IllegalStateException( format(
				"Could not find @Column annotation, (location: %s.%s)",
				parameters.get( DynamicParameterizedType.ENTITY ),
				parameters.get( DynamicParameterizedType.PROPERTY )
		) );
	}

	@Override
	protected SqlTypeDescriptor makeSqlTypeDescriptor() {
		return org.hibernate.type.BigDecimalType.INSTANCE.getSqlTypeDescriptor();
	}

	@Override
	protected JavaTypeDescriptor<BigDecimal> makeJavaTypeDescriptor() {
		if ( scale == null ) {
			throw new IllegalStateException( "scale must have been set" );
		}
		return new BigDecimalRoundingJavaTypeDescriptor( scale );
	}
}
