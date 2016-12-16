package org.hibernate.dialect.pagination;

import static java.lang.String.format;
import static java.util.regex.Matcher.quoteReplacement;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.engine.spi.RowSelection;
import org.hibernate.internal.util.StringHelper;

/**
 * LIMIT clause handler compatible with SQL Server 2005 and later.
 *
 * @author Lukasz Antoniak (lukasz dot antoniak at gmail dot com)
 */
public class SQLServer2005LimitHandler extends AbstractLimitHandler {
	private static final String SELECT = "select";
	private static final String SELECT_WITH_SPACE = SELECT + ' ';
	private static final String FROM = "from";
	private static final String DISTINCT = "distinct";
	private static final String ORDER_BY = "order by";

	private static final Pattern ALIAS_PATTERN = Pattern.compile( "(?i)\\sas\\s(.)+$" );
	private static final Pattern MULTI_PART_IDENTIFIER = Pattern.compile("\\b\\w+\\.\\w+\\b");

	private boolean topAdded = false; // Flag indicating whether TOP(?) expression has been added to the original query.
	private boolean hasOffset = true; // True if offset greater than 0.

	public SQLServer2005LimitHandler(String sql, RowSelection selection) {
		super( sql, selection );
	}

	@Override
	public boolean supportsLimit() {
		return true;
	}

	@Override
	public boolean useMaxForLimit() {
		return true;
	}

	@Override
	public boolean supportsLimitOffset() {
		return true;
	}

	@Override
	public boolean supportsVariableLimit() {
		return true;
	}

	@Override
	public int convertToFirstRowValue(int zeroBasedFirstResult) {
		// Our dialect paginated results aren't zero based. The first row should get the number 1 and so on
		return zeroBasedFirstResult + 1;
	}

	/**
	 * Add a LIMIT clause to the given SQL SELECT (HHH-2655: ROW_NUMBER for Paging)
	 *
	 * The LIMIT SQL will look like:
	 *
	 * <pre>
	 * WITH query AS (
	 *   SELECT inner_query.*
	 *        , ROW_NUMBER() OVER (ORDER BY orignal_order_by_or_constant) as __hibernate_row_nr__
	 *     FROM ( original_query_with_top_if_order_by_present_and_single_claused ) inner_query
	 * )
	 * SELECT alias_list FROM query WHERE __hibernate_row_nr__ >= offset AND __hibernate_row_nr__ < offset + last
	 * ORDER BY __hibernate_row_nr__
	 * </pre>
	 *
	 * When offset equals {@literal 0}, only {@literal TOP(?)} expression is added to the original query.
	 *
	 * @return A new SQL statement with the LIMIT clause applied.
	 */
	@Override
	public String getProcessedSql() {
		StringBuilder sb = new StringBuilder( sql );
		if ( sb.charAt( sb.length() - 1 ) == ';' ) {
			sb.setLength( sb.length() - 1 );
		}

		int orderByIndex = shallowIndexOfWord( sb, ORDER_BY, 0 );
		String orderByClause = orderByIndex == -1 ? null : sb.substring( orderByIndex, sb.length() );

		if ( LimitHelper.hasFirstRow( selection ) || orderByRequiresRowNumberOver( orderByClause ) ) {
			if ( orderByRequiresRowNumberOver( orderByClause ) ) {
				// Remove ORDER BY from the query if it can cause trouble to SQL Server (e.g. repeated columns)
				sb.delete( orderByIndex, orderByIndex + orderByClause.length() );
				orderByIndex = -1; // TOP will not be added
			}
			final String selectClause = fillAliasInSelectClause( sb );

			orderByClause = replaceMultiPartIdentifiersInOrderBy( sb, orderByClause );

			if ( orderByIndex > 0 ) {
				// ORDER BY requires using TOP.
				addTopExpression( sb );
			}

			encloseWithOuterQuery( sb, orderByClause );

			// Wrap the query within a with statement:
			sb.insert( 0, "WITH query AS (" ).append( ") SELECT " ).append( selectClause ).append( " FROM query " );
			sb.append( "WHERE __hibernate_row_nr__ >= ? AND __hibernate_row_nr__ < ? order by __hibernate_row_nr__" );
		}
		else {
			hasOffset = false;
			addTopExpression( sb );
		}

		return sb.toString();
	}


	protected boolean orderByRequiresRowNumberOver(String orderByClause) {
		if ( orderByClause == null ) {
			// no ORDER BY
			return false;
		}
		if ( shallowIndexOf( orderByClause, ",", 0 ) != -1 ) {
			// multiple values
			return true;
		}
		return false;
	}

	/**
	 * Replaces "multi-part identifiers" with query aliases, adding new aliases when necessary.
	 * <p>
	 * If original {@code ORDER BY} clause references table columns directly (e.g. {@code TABLE.SOMECOLUMN}), such
	 * reference is no longer valid when used in {@code ROW_NUMBER() OVER (ORDER BY ...)} in an outer query. All such
	 * references need to be replaced with a query alias. This method reuses query aliases if they were defined in the
	 * original query and adds new aliases if required.
	 * 
	 * @param sb original query; can be modified
	 * @param orderByClause order by clause extracted from the {@code sb} query
	 * @return new order by clause to be used in {@code ROW_NUMBER() OVER (ORDER BY ...)}
	 * @author Piotr Findeisen <piotr.findeisen@gmail.com>
	 */
	private String replaceMultiPartIdentifiersInOrderBy(StringBuilder sb, String orderByClause) {
		if ( orderByClause == null ) {
			return null;
		}
		Matcher multiPartIdentifierMatcher = MULTI_PART_IDENTIFIER.matcher( orderByClause );
		StringBuffer newOrderBy = new StringBuffer();
		int unique = 0;
		boolean multiPartIdentifierFound = false;
		int newAliasInsertionPoint = -1; // computed lazily
		while ( multiPartIdentifierMatcher.find() ) {
			multiPartIdentifierFound = true;
			String expression = multiPartIdentifierMatcher.group( 0 );
			Pattern findAliasPattern = Pattern.compile( "\\b" + Pattern.quote( expression ) + "\\b\\s+(?i:as)\\s+(\\w+)" );
			Matcher findAliasMatcher = findAliasPattern.matcher( sb );
			String aliasToUse;
			if ( findAliasMatcher.find() ) {
				aliasToUse = findAliasMatcher.group( 1 );
			}
			else {
				// Add new alias to the query and use it.
				String newAlias = StringHelper.generateAlias( "mpialias", unique++ );
				// final int selectEndIndex = sb.indexOf( "select" ) + "select".length();
				if ( newAliasInsertionPoint == -1 ) {
					newAliasInsertionPoint = shallowIndexOfWord( sb, FROM, 0 );
					if ( newAliasInsertionPoint == -1 ) {
						throw new IllegalStateException( format( "Cannot find %s in query: %s", FROM, sb ) );
					}
				}
				sb.insert( newAliasInsertionPoint, format( ", %s AS %s ", expression, newAlias ) );
				aliasToUse = newAlias;
			}
			multiPartIdentifierMatcher.appendReplacement( newOrderBy, quoteReplacement( aliasToUse ) );
		}

		if ( multiPartIdentifierFound ) {
			multiPartIdentifierMatcher.appendTail( newOrderBy );
			orderByClause = newOrderBy.toString();
		}

		return orderByClause;
	}

	@Override
	public int bindLimitParametersAtStartOfQuery(PreparedStatement statement, int index) throws SQLException {
		if ( topAdded ) {
			statement.setInt( index, getMaxOrLimit() - 1 ); // Binding TOP(?).
			return 1;
		}
		return 0;
	}

	@Override
	public int bindLimitParametersAtEndOfQuery(PreparedStatement statement, int index) throws SQLException {
		return hasOffset ? super.bindLimitParametersAtEndOfQuery( statement, index ) : 0;
	}

	/**
	 * Adds missing aliases in provided SELECT clause and returns coma-separated list of them.
	 * If query takes advantage of expressions like {@literal *} or {@literal {table}.*} inside SELECT clause,
	 * method returns {@literal *}.
	 *
	 * @param sb SQL query.
	 *
	 * @return List of aliases separated with comas or {@literal *}.
	 */
	protected String fillAliasInSelectClause(StringBuilder sb) {
		final List<String> aliases = new LinkedList<String>();
		final int startPos = shallowIndexOf( sb, SELECT_WITH_SPACE, 0 );
		int endPos = shallowIndexOfWord( sb, FROM, startPos );
		int nextComa = startPos;
		int prevComa = startPos;
		int unique = 0;
		boolean selectsMultipleColumns = false;

		while ( nextComa != -1 ) {
			prevComa = nextComa;
			nextComa = shallowIndexOf( sb, ",", nextComa );
			if ( nextComa > endPos ) {
				break;
			}
			if ( nextComa != -1 ) {
				String expression = sb.substring( prevComa, nextComa );
				if ( selectsMultipleColumns( expression ) ) {
					selectsMultipleColumns = true;
				}
				else {
					String alias = getAlias( expression );
					if ( alias == null ) {
						// Inserting alias. It is unlikely that we would have to add alias, but just in case.
						alias = StringHelper.generateAlias( "page", unique );
						sb.insert( nextComa, " as " + alias );
						int aliasExprLength = ( " as " + alias ).length();
						++unique;
						nextComa += aliasExprLength;
						endPos += aliasExprLength;
					}
					aliases.add( alias );
				}
				++nextComa;
			}
		}
		// Processing last column.
		endPos = shallowIndexOfWord( sb, FROM, startPos ); // Refreshing end position, because we might have inserted new alias.
		String expression = sb.substring( prevComa, endPos );
		if ( selectsMultipleColumns( expression ) ) {
			selectsMultipleColumns = true;
		}
		else {
			String alias = getAlias( expression );
			if ( alias == null ) {
				// Inserting alias. It is unlikely that we would have to add alias, but just in case.
				alias = StringHelper.generateAlias( "page", unique );
				sb.insert( endPos - 1, " as " + alias );
			}
			aliases.add( alias );
		}

		// In case of '*' or '{table}.*' expressions adding an alias breaks SQL syntax, returning '*'.
		return selectsMultipleColumns ? "*" : StringHelper.join( ", ", aliases.iterator() );
	}

	/**
	 * @param expression Select expression.
	 *
	 * @return {@code true} when expression selects multiple columns, {@code false} otherwise.
	 */
	private boolean selectsMultipleColumns(String expression) {
		final String lastExpr = expression.trim().replaceFirst( "(?i)(.)*\\s", "" );
		return "*".equals( lastExpr ) || lastExpr.endsWith( ".*" );
	}

	/**
	 * Returns alias of provided single column selection or {@code null} if not found.
	 * Alias should be preceded with {@code AS} keyword.
	 *
	 * @param expression Single column select expression.
	 *
	 * @return Column alias.
	 */
	private String getAlias(String expression) {
		Matcher matcher = ALIAS_PATTERN.matcher( expression );
		if ( matcher.find() ) {
			// Taking advantage of Java regular expressions greedy behavior while extracting the last AS keyword.
			// Note that AS keyword can appear in CAST operator, e.g. 'cast(tab1.col1 as varchar(255)) as col1'.
			return matcher.group( 0 ).replaceFirst( "(?i)(.)*\\sas\\s", "" ).trim();
		}
		return null;
	}

	/**
	 * Encloses original SQL statement with outer query that provides {@literal __hibernate_row_nr__} column.
	 *
	 * @param sql SQL query.
	 */
	protected void encloseWithOuterQuery(StringBuilder sql, String orderByClause) {
		if (orderByClause != null) {
			orderByClause = orderByClause.substring(ORDER_BY.length() + 1);
		} else {
			orderByClause = "current_timestamp";
		}
		sql.insert( 0, "SELECT inner_query.*, ROW_NUMBER() OVER (ORDER BY " + orderByClause + ") as __hibernate_row_nr__ FROM ( " );
		sql.append( " ) inner_query " );
	}

	/**
	 * Adds {@code TOP} expression. Parameter value is bind in
	 * {@link #bindLimitParametersAtStartOfQuery(PreparedStatement, int)} method.
	 *
	 * @param sql SQL query.
	 */
	protected void addTopExpression(StringBuilder sql) {
		final int distinctStartPos = shallowIndexOfWord( sql, DISTINCT, 0 );
		if ( distinctStartPos > 0 ) {
			// Place TOP after DISTINCT.
			sql.insert( distinctStartPos + DISTINCT.length(), " TOP(?)" );
		}
		else {
			final int selectStartPos = shallowIndexOf( sql, SELECT_WITH_SPACE, 0 );
			// Place TOP after SELECT.
			sql.insert( selectStartPos + SELECT.length(), " TOP(?)" );
		}
		topAdded = true;
	}

	/**
	 * Returns index of the first case-insensitive match of search term surrounded by spaces
	 * that is not enclosed in parentheses.
	 *
	 * @param sb String to search.
	 * @param search Search term.
	 * @param fromIndex The index from which to start the search.
	 *
	 * @return Position of the first match, or {@literal -1} if not found.
	 */
	private static int shallowIndexOfWord(final CharSequence sb, final String search, int fromIndex) {
		final int index = shallowIndexOf( sb, ' ' + search + ' ', fromIndex );
		return index != -1 ? ( index + 1 ) : -1; // In case of match adding one because of space placed in front of search term.
	}

	/**
	 * Returns index of the first case-insensitive match of search term that is not enclosed in parentheses.
	 *
	 * @param sb String to search.
	 * @param search Search term.
	 * @param fromIndex The index from which to start the search.
	 *
	 * @return Position of the first match, or {@literal -1} if not found.
	 */
	private static int shallowIndexOf(CharSequence sb, String search, int fromIndex) {
		final String lowercase = sb.toString().toLowerCase(Locale.US); // case-insensitive match
		final int len = lowercase.length();
		final int searchlen = search.length();
		int pos = -1, depth = 0, cur = fromIndex;
		do {
			pos = lowercase.indexOf( search, cur );
			if ( pos != -1 ) {
				for ( int iter = cur; iter < pos; iter++ ) {
					char c = sb.charAt( iter );
					if ( c == '(' ) {
						depth = depth + 1;
					}
					else if ( c == ')' ) {
						depth = depth - 1;
					}
				}
				cur = pos + searchlen;
			}
		} while ( cur < len && depth != 0 && pos != -1 );
		return depth == 0 ? pos : -1;
	}
}
