/**
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.neo4j.repository;

import org.neo4j.cypherdsl.grammar.Execute;
import org.neo4j.cypherdsl.grammar.Skip;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.graphdb.index.ReadableIndex;
import org.neo4j.helpers.collection.ClosableIterable;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.neo4j.annotation.QueryType;
import org.springframework.data.neo4j.conversion.EndResult;
import org.springframework.data.neo4j.repository.query.CypherQuery;
import org.springframework.data.neo4j.support.Neo4jTemplate;
import org.springframework.data.neo4j.support.query.QueryEngine;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static java.lang.String.format;
import static org.neo4j.helpers.collection.MapUtil.map;

/**
 * Repository like finder for Node and Relationship-Entities. Provides finder methods for direct access, access via {@link org.springframework.data.neo4j.core.TypeRepresentationStrategy}
 * and indexing.
 *
 * @param <T> GraphBacked target of this finder, enables the finder methods to return this concrete type
 * @param <S> Type of backing state, either Node or Relationship
 */
@Transactional(readOnly = true)
public abstract class AbstractGraphRepository<S extends PropertyContainer, T> implements GraphRepository<T>, NamedIndexRepository<T>, SpatialRepository<T>, CypherDslRepository<T> {
    private final LegacyIndexSearcher<S,T> legacyIndexSearcher;

    /*
    index.query( LayerNodeIndex.WITHIN_WKT_GEOMETRY_QUERY,
                    "withinWKTGeometry:POLYGON ((15 56, 15 57, 16 57, 16 56, 15 56))" );

     hits = index.query( LayerNodeIndex.WITHIN_WKT_GEOMETRY_QUERY,
                     "POLYGON ((15 56, 15 57, 16 57, 16 56, 15 56))" ); lon,lat
             assertTrue( hits.hasNext() );
        final String poly = String.format("POLYGON (())", lowerLeftLon, upperRightLon, lowerLeftLat, upperRightLat);
     */

    @Override
    public EndResult<T> findWithinWellKnownText( final String indexName, String wellKnownText) {
        return legacyIndexSearcher.geoQuery(indexName, "withinWKTGeometry", wellKnownText);
    }
    @Override
    public EndResult<T> findWithinDistance( final String indexName, final double lat, double lon, double distanceKm) {
        return legacyIndexSearcher.geoQuery(indexName, "withinDistance", map("point", new Double[] { lon, lat}, "distanceInKm", distanceKm));
    }

    @Override
    public EndResult<T> findWithinBoundingBox(final String indexName, final double lowerLeftLat,
                                                     final double lowerLeftLon, final double upperRightLat, final double upperRightLon) {
        return legacyIndexSearcher.geoQuery(indexName, "bbox", format("[%s, %s, %s, %s]", lowerLeftLon, upperRightLon, lowerLeftLat, upperRightLat));
    }

    interface Query<S extends PropertyContainer> {
        IndexHits<S> query(ReadableIndex<S> index);
    }

    protected T createEntity(S node) {
        return template.createEntityFromState(node, clazz, template.getMappingPolicy(clazz));
    }

    public static final ClosableIterable EMPTY_CLOSABLE_ITERABLE = new ClosableIterable() {
        @Override
        public void close() {
        }

        @Override
        public Iterator<?> iterator() {
            return Collections.emptyList().iterator();
        }
    };
    /**
     * Target graphbacked type
     */
    protected final Class<T> clazz;
    protected final Neo4jTemplate template;

    public AbstractGraphRepository(final Neo4jTemplate template, final Class<T> clazz) {
        this.template = template;
        this.clazz = clazz;
        legacyIndexSearcher = new LegacyIndexSearcher<>(template,clazz);
    }

    @Override
    @Transactional
    public <U extends T> U save(U entity) {
        return template.save(entity);
    }

    @Override
    @Transactional
    public <U extends T> Iterable<U> save(Iterable<U> entities) {
        for (U entity : entities) {
            save(entity);
        }
        return entities;
    }
    
    /**
     * @return Number of instances of the target type in the graph.
     */
    @Override
    public long count() {
        return template.count(clazz);
    }

    /**
     * @return lazy Iterable over all instances of the target type.
     */
    @Override
    public EndResult<T> findAll() {
        return template.findAll(clazz);
    }

    /**
     *
     * @param id id
     * @return Entity with the given id or null.
     */
    @Override
    public T findOne(final Long id) {
        try {
            return createEntity(getById(id));
        } catch (DataRetrievalFailureException e) {
            return null;
        }
    }

    /**
     * Index based single finder, uses the default index name for this type (short class name).
     *
     * @param property
     * @param value
     * @return Single Entity with this property and value
     */
    @Override
    public T findByPropertyValue(final String property, final Object value) {
        return findByPropertyValue(null,property,value);
    }
    /**
     * Index based single finder.
     *
     * @param indexName or null for default
     * @param property
     * @param value
     * @return Single Entity with this property and value
     */
    @Override
    @Deprecated
    public T findByPropertyValue(final String indexName, final String property, final Object value) {
        return legacyIndexSearcher.findByPropertyValue(indexName, property, value);

    }

    /**
     * Index based exact finder.
     *
     * @param indexName or null for default index
     * @param property
     * @param value
     * @return Iterable over Entities with this property and value
     */
    @Override
    @Deprecated
    public EndResult<T> findAllByPropertyValue(final String indexName, final String property, final Object value) {
        return legacyIndexSearcher.findAllByPropertyValue(indexName, property, value);
    }

    /**
     * Index based exact finder, uses the default index name for this type (short class name).
     * @param property
     * @param value
     * @return Iterable over Entities with this property and value
     */
    @Override
    public EndResult<T> findAllByPropertyValue(final String property, final Object value) {
        return findAllByPropertyValue(null, property, value);
    }

    /**
     * Index based fulltext / query object finder, uses the default index name for this type (short class name).
     *
     * @param key key of the field to query
     *@param query lucene query object or query-string  @return Iterable over Entities with this property and value
     */
    @Override
    @Deprecated
    public EndResult<T> findAllByQuery(final String key, final Object query) {
        return findAllByQuery(null, key,query);
    }
    /**
     * Index based fulltext / query object finder.
     *
     * @param indexName or null for default index
     * @param property property of the field to query
     *@param query lucene query object or query-string  @return Iterable over Entities with this property and value
     */
    @Override
    @Deprecated
    public EndResult<T> findAllByQuery(final String indexName, final String property, final Object query) {
        return legacyIndexSearcher.findAllByQuery(indexName, property, query);
    }

    @Override
    @Deprecated
    public EndResult<T> findAllByRange(final String property, final Number from, final Number to) {
        return findAllByRange(null,property,from,to);
    }
    @Override
    @Deprecated
    public EndResult<T> findAllByRange(final String indexName, final String property, final Number from, final Number to) {
        return legacyIndexSearcher.findAllByRange(indexName, property, from, to);
    }


    protected abstract S getById(long id);

    @Override
    public boolean exists(Long id) {
        try {
            return getById(id)!=null;
        } catch (DataRetrievalFailureException e) {
            return false;
        }
    }

    @Override
    public Class getStoredJavaType(Object entity) {
        return template.getStoredJavaType(entity);
    }

    @Override
    @Transactional
    public void delete(T entity) {
        template.delete(entity);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        delete(findOne(id));
    }

    @Override
    @Transactional
    public void delete(Iterable<? extends T> entities) {
        for (T entity : entities) {
            delete(entity);
        }
    }

    @Override
    @Transactional
    public void deleteAll() {
        delete(findAll());
    }

    @Override
    public EndResult<T> findAll(Sort sort) {
        CypherQuery cq = new CypherQuery(template.getEntityType(clazz).getEntity(),template, template.isLabelBased());
        return query(cq.toQueryString(sort), Collections.EMPTY_MAP);
    }

    @Override
    public EndResult<T> query(String query, Map<String, Object> params) {
        return template.query(query, params).to(clazz);
    }

    @Override
    public Page<T> findAll(final Pageable pageable) {
        int count = pageable.getPageSize();
        int offset = pageable.getOffset();
        EndResult<T> foundEntities = findAll(pageable.getSort());
        final Iterator<T> iterator = foundEntities.iterator();
        final PageImpl<T> page = extractPage(pageable, count, offset, iterator);
        foundEntities.finish();
        return page;
    }

    @Override
    public Iterable<T> findAll(final Iterable<Long> ids) {
        return new Iterable<T>() {
            @Override
            public Iterator<T> iterator() {
                final Iterator<Long> idIterator = ids.iterator();
                return new Iterator<T>() {

                     @Override
                     public boolean hasNext() {
                         return idIterator.hasNext();
                     }

                     @Override
                     public T next() {
                          return template.findOne(idIterator.next(), clazz);
                     }

                     @Override
                     public void remove() {
                          throw new UnsupportedOperationException();
                     }
                };
            }
        };
    }

    private PageImpl<T> extractPage(Pageable pageable, int count, int offset, Iterator<T> iterator) {
        final List<T> result = new ArrayList<T>(count);
        int total=subList(offset, count, iterator, result);
        if (iterator.hasNext()) total++;
        return new PageImpl<T>(result, pageable, total);
    }

    private int subList(int skip, int limit, Iterator<T> source, final List<T> list) {
        int count=0;
        while (source.hasNext()) {
            count++;
            T t = source.next();
            if (skip > 0) {
                skip--;
            } else {
                list.add(t);
                limit--;
            }
            if (limit + skip == 0) break;
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Page<T> query(Execute query, Execute countQuery, Map<String, Object> params, Pageable page) {
        final Execute limitedQuery = ((Skip)query).skip(page.getOffset()).limit(page.getPageSize());
        QueryEngine<Object> engine = template.queryEngineFor(QueryType.Cypher);
        Page result = engine.query(limitedQuery.toString(), params).to(clazz).as(Page.class);
        if (countQuery == null) {
            return result; 
        }
        Long count = engine.query(countQuery.toString(), params).to(Long.class).singleOrNull();
        if (count==null) return result;
        return new PageImpl<T>(result.getContent(),page, count);
    }
    @SuppressWarnings("unchecked")
    @Override
    public Page<T> query(Execute query, Map<String, Object> params, Pageable page) {
        return query(query, null, params, page);
    }

    @SuppressWarnings("unchecked")
    @Override
    public EndResult<T> query(Execute query, Map<String, Object> params) {
        return template.queryEngineFor(QueryType.Cypher).query(query.toString(), params).to(clazz);
    }
}
