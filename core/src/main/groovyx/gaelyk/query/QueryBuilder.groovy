package groovyx.gaelyk.query

import com.google.appengine.api.datastore.Query
import com.google.appengine.api.datastore.PreparedQuery
import com.google.appengine.api.datastore.DatastoreServiceFactory
import com.google.appengine.api.datastore.FetchOptions
import com.google.appengine.api.datastore.Key
import com.google.appengine.api.datastore.Cursor

/**
 * The query build is used to create a datastore <code>Query</code>
 * or execute a <code>PreparedQuery</code>,
 * from the <code>datastore.query {}</code> or <code>datastore.execute {}</code> calls.
 *
 * @author Guillaume Laforge
 *
 * @since 1.0
 */
class QueryBuilder {
    private QueryType queryType = QueryType.ALL
    private String fromKind
    private Key ancestor
    @PackageScope Class coercedClass
    @PackageScope List<Clause> clauses = []
    private FetchOptions options = FetchOptions.Builder.withDefaults()

    /**
     * @return a <code>Query</code> object
     */
    Query createQuery() {
        // entity query, or kindless query
        Query query = fromKind ? new Query(fromKind) : new Query()

        // retrieve keys only or full entities
        if (queryType == QueryType.KEYS)
            query.setKeysOnly()

        if (ancestor)
            query.setAncestor(ancestor)

        for (clause in clauses) {
            if (clause instanceof WhereClause) {
                WhereClause whereClause = clause
                query.addFilter(whereClause.column, whereClause.operation, whereClause.comparedValue)
            } else if (clause instanceof SortClause) {
                SortClause sortClause = clause
                query.addSort(sortClause.column, sortClause.direction)
            }
        }
        return query
    }

    /**
     * @return the result of the execution of a prepared query
     */
    def execute() {
        Query query = createQuery()

        PreparedQuery preparedQuery = DatastoreServiceFactory.datastoreService.prepare(query)

        if (queryType == QueryType.COUNT) {
            return preparedQuery.countEntities(options)
        } else if (queryType == QueryType.SINGLE) {
            if (coercedClass) {
                return preparedQuery.asSingleEntity()?.asType(coercedClass)
            } else {
                return preparedQuery.asSingleEntity()
            }
        }

        if (coercedClass) {
            def entities = preparedQuery.asQueryResultIterator(options)
            // use "manual" collect{} as in the context of the query{} call
            // the delegation transforms the class into a string expression
            def result = []
            for (entity in entities) result << entity.asType(coercedClass)
            return result
        } else {
            return preparedQuery.asList(options)
        }
    }

    /**
     * @param name the name of the property to be retrieved from within the closue passed to query/execute()
     * @return the all, keys, single or count query type constants or a string representing the property
     */
    def getProperty(String name) {
        if (name == 'all')    return QueryType.ALL
        if (name == 'keys')   return QueryType.KEYS
        if (name == 'single') return QueryType.SINGLE
        if (name == 'count')  return QueryType.COUNT
        return name
    }

    /**
     * Select all entity properties, keys only, a single entity, or the count of entities matching that query.
     * By default, if the select clause is not used, the query builder assumes a select all.
     * Possible syntax:
     * <pre><code>
     *  select all
     *  select keys
     *  select single
     *  select count
     * </code></pre>
     *
     * @param qt the type of query
     * @return the query builder for chaining calls
     */
    QueryBuilder select(QueryType qt) {
        queryType = qt
        return this
    }

    /**
     * @throws QuerySyntaxException if a wrong parameter is passed to the select clause.
     */
    void select(Object qt) {
        throw new QuerySyntaxException("Use 'all', 'keys', 'single' or 'count' for your select clause instead of ${qt}.")
    }

    /**
     * Defines the entity kind we want to retrieve.
     *
     * Possible syntax:
     * <pre><code>
     *  from persons
     *  from 'persons'
     *  from someStringVariable
     * </code></pre>
     *
     * @param entityKind the kind of the entity we want to search for
     * @return the query builder for chaining calls
     */
    QueryBuilder from(String entityKind) {
        fromKind = entityKind
        return this
    }

    /**
     * Defines the entity kind we want to retrieve,
     * and specifies that we want to coerce the <code>Entity</code> into a specific class.
     *
     * Possible syntax:
     * <pre><code>
     *  from persons as Person
     *  from 'persons' as Person
     *  from someStringVariable as SomeClass
     * </code></pre>
     *
     * @param entityKind the entity kind
     * @param coercedTo the class into which we want to coerce the entities
     * @return the query builder for chaining calls
     */
    QueryBuilder from(String entityKind, Class coercedTo) {
        fromKind = entityKind
        coercedClass = coercedTo
        return this
    }

    /**
     * Specify finding entities descending a certain ancestor
     *
     * Possible syntax:
     * <pre><code>
     *  ancestor someKeyVariable
     * </code></pre>
     *
     * @param key the key of the ancestor
     * @return the query builder for chaining calls
     */
    QueryBuilder ancestor(Key key) {
        ancestor = key
        return this
    }

    /**
     * Specify sorting on a property and its direction.
     *
     * Possible syntax:
     * <pre><code>
     *  sort asc by age
     *  sort desc by dateCreated
     * </code></pre>
     *
     * @param direction asc or desc for ascending and descending sorting respectively
     * @return a sort clause on which the <code>by()</code> method can be called
     */
    SortClause sort(String direction) {
        Query.SortDirection dir
        if (direction == 'asc') {
            dir =  Query.SortDirection.ASCENDING
        } else if (direction == 'desc') {
            dir =  Query.SortDirection.DESCENDING
        } else {
            throw new QuerySyntaxException("Use either 'asc' or 'desc' for sort direction.")
        }

        return new SortClause(
            builder: this,
            direction: dir
        )
    }

    /**
     * @throws QuerySyntaxException when a wrong direction is being used
     */
    void sort(Object direction) {
        throw new QuerySyntaxException("Use either 'asc' or 'desc' for sort direction.")
    }

    /**
     * Defines a where clause for comparing an entity property to another value.
     * The left-hand-side of the comparison should be the column,
     * and the right-hand-side the value to be compared against.
     * The following operators are supported: <, <=, ==, >=, >, != and in
     *
     * Possible syntax:
     * <pre><code>
     *  where dateCreated > new Date() - 1
     *  where name == 'Guillaume'
     *  where numChildren in [0, 1]
     *  where color != 'red'
     * </code></pre>
     *
     * @param clause the where clause
     * @return the query builder for chaining calls
     */
    QueryBuilder where(WhereClause clause) {
        if (coercedClass) {
            if (!coercedClass.metaClass.properties.name.contains(clause.column)) {
                throw new QuerySyntaxException("Your where clause on '${clause.column}' is not possible as ${coercedClass.name} doesn't contain that property")
            }
        }
        this.clauses.add(clause)
        return this
    }

    /**
     * A synonym of where.
     *
     * @param clause the where clause
     * @return the query builder for chaining calls
     */
    QueryBuilder and(WhereClause clause) {
        where(clause)
    }

    /**
     * @throws QuerySyntaxException when something different than a where clause is given
     */
    void where(Object clause) {
        throw new QuerySyntaxException("Use a proper comparison in your where/and clause, instead of ${clause}")
    }

    // ------------------------------------------------
    // fetch options

    /**
     * Defines a limit fetch option for the <code>PreparedQuery</code>
     *
     * Possible syntax:
     * <pre><code>
     *  limit 10
     *  offset 50 limit 10
     * </code></pre>
     *
     * @param lim the limit
     * @return the query builder for chaining calls
     */
    QueryBuilder limit(int lim) {
        options.limit(lim)
        return this
    }

    /**
     * Defines an offset fetch option for the <code>PreparedQuery</code>
     *
     * Possible syntax:
     * <pre><code>
     *  offset 10
     *  offset 50 limit 10
     * </code></pre>
     *
     * @param ofst the offset
     * @return the query builder for chaining calls
     */
    QueryBuilder offset(int ofst) {
        options.offset(ofst)
        return this
    }

    /**
     * Defines a range, ie. an offset and limit fetch options for the <code>PreparedQuery</code>
     *
     * Possible syntax:
     * <pre><code>
     *  range 10..20
     * </code></pre>
     *
     * @param range an int range
     * @return the query builder for chaining calls
     */
    QueryBuilder range(IntRange range) {
        options.offset(range.getFromInt())
        options.limit(range.getToInt() - options.offset + 1)
        return this
    }

    /**
     * Defines a chunk size fetch option for the <code>PreparedQuery</code>
     *
     * @param size size of the chunks
     * @return the query builder for chaining calls
     */
    QueryBuilder chunkSize(int size) {
        options.chunkSize(size)
        return this
    }

    /**
     * Defines a prefetch size fetch option for the <code>PreparedQuery</code>
     *
     * Possible syntax:
     * <pre><code>
     *  offset 10
     *  offset 50 limit 10
     * </code></pre>
     *
     * @param size the prefetch size
     * @return the query builder for chaining calls
     */
    QueryBuilder prefetchSize(int size) {
        options.prefetchSize(size)
        return this
    }

    /**
     * Defines a start cursor fetch option for the <code>PreparedQuery</code>
     *
     * Possible syntax:
     * <pre><code>
     *  startAt someCursorVariable
     * </code></pre>
     *
     * @param cursor the start cursor
     * @return the query builder for chaining calls
     */
    QueryBuilder startAt(Cursor cursor) {
        options.startCursor(cursor)
        return this
    }

    /**
     * Defines a start cursor fetch option for the <code>PreparedQuery</code>
     * using a string representation of the cursor
     *
     * Possible syntax:
     * <pre><code>
     *  startAt cursorString
     * </code></pre>
     *
     * @param cursor the start cursor in its string representation
     * @return the query builder for chaining calls
     */
    QueryBuilder startAt(String cursorString) {
        return startAt(Cursor.fromWebSafeString(cursorString))
    }

    /**
     * Defines an end cursor fetch option for the <code>PreparedQuery</code>
     *
     * Possible syntax:
     * <pre><code>
     *  endAt someCursorVariable
     * </code></pre>
     *
     * @param cursor the end cursor
     * @return the query builder for chaining calls
     */
    QueryBuilder endAt(Cursor cursor) {
        options.endCursor(cursor)
        return this
    }

    /**
     * Defines an end cursor fetch option for the <code>PreparedQuery</code>
     * using a string representation of the cursor
     *
     * Possible syntax:
     * <pre><code>
     *  endAt cursorString
     * </code></pre>
     *
     * @param cursor the end cursor
     * @return the query builder for chaining calls
     */
    QueryBuilder endAt(String cursorString) {
        return endAt(Cursor.fromWebSafeString(cursorString))
    }
}