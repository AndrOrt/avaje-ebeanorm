package com.avaje.ebeaninternal.server.query;

import com.avaje.ebeaninternal.server.core.QueryIterator;
import com.avaje.ebean.ValuePair;
import com.avaje.ebean.Version;
import com.avaje.ebean.bean.BeanCollection;
import com.avaje.ebean.bean.EntityBean;
import com.avaje.ebean.bean.ObjectGraphNode;
import com.avaje.ebean.config.dbplatform.DatabasePlatform;
import com.avaje.ebeaninternal.api.BeanIdList;
import com.avaje.ebeaninternal.api.SpiQuery;
import com.avaje.ebeaninternal.server.core.DiffHelp;
import com.avaje.ebeaninternal.server.core.OrmQueryRequest;
import com.avaje.ebeaninternal.server.deploy.BeanDescriptor;
import com.avaje.ebeaninternal.server.lib.util.Str;
import com.avaje.ebeaninternal.server.persist.Binder;
import com.avaje.ebeaninternal.server.transaction.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the Object Relational fetching.
 */
public class CQueryEngine {

  private static final Logger logger = LoggerFactory.getLogger(CQueryEngine.class);

  private static final int defaultSecondaryQueryBatchSize = 100;

  private static final String T0 = "t0";

  private final boolean forwardOnlyHintOnFindIterate;

  private final CQueryBuilder queryBuilder;

  private final CQueryHistorySupport historySupport;

  public CQueryEngine(DatabasePlatform dbPlatform, Binder binder, Map<String, String> asOfTableMapping, String asOfSysPeriod, Map<String, String> draftTableMap) {
    this.forwardOnlyHintOnFindIterate = dbPlatform.isForwardOnlyHintOnFindIterate();
    this.historySupport = new CQueryHistorySupport(dbPlatform.getHistorySupport(), asOfTableMapping, asOfSysPeriod);
    this.queryBuilder = new CQueryBuilder(dbPlatform, binder, historySupport, new CQueryDraftSupport(draftTableMap));
  }

  public <T> CQuery<T> buildQuery(OrmQueryRequest<T> request) {
    return queryBuilder.buildQuery(request);
  }

  public <T> int delete(OrmQueryRequest<T> request) {
    CQueryUpdate query = queryBuilder.buildUpdateQuery("Delete", request);
    return executeUpdate(request, query);
  }

  public <T> int update(OrmQueryRequest<T> request) {
    CQueryUpdate query = queryBuilder.buildUpdateQuery("Update", request);
    return executeUpdate(request, query);
  }

  private <T> int executeUpdate(OrmQueryRequest<T> request, CQueryUpdate query) {
    try {
      int rows = query.execute();

      if (request.isLogSql()) {
        String logSql = query.getGeneratedSql();
        if (TransactionManager.SQL_LOGGER.isTraceEnabled()) {
          logSql = Str.add(logSql, "; --bind(", query.getBindLog(), ") rows:", String.valueOf(rows));
        }
        request.logSql(logSql);
      }

      return rows;

    } catch (SQLException e) {
      throw CQuery.createPersistenceException(e, request.getTransaction(), query.getBindLog(), query.getGeneratedSql());
    }
  }

  /**
   * Build and execute the find Id's query.
   */
  public <T> BeanIdList findIds(OrmQueryRequest<T> request) {

    CQueryFetchIds rcQuery = queryBuilder.buildFetchIdsQuery(request);
    try {

      BeanIdList list = rcQuery.findIds();

      if (request.isLogSql()) {
        String logSql = rcQuery.getGeneratedSql();
        if (TransactionManager.SQL_LOGGER.isTraceEnabled()) {
          logSql = Str.add(logSql, "; --bind(", rcQuery.getBindLog(), ")");
        }
        request.logSql(logSql);
      }

      if (request.isLogSummary()) {
        request.getTransaction().logSummary(rcQuery.getSummary());
      }

      if (request.getQuery().isFutureFetch()) {
        // end the transaction for futureFindIds (it had it's own one)
        logger.debug("Future findIds completed!");
        request.getTransaction().end();
      }

      return list;

    } catch (SQLException e) {
      throw CQuery.createPersistenceException(e, request.getTransaction(), rcQuery.getBindLog(), rcQuery.getGeneratedSql());
    }
  }

  /**
   * Build and execute the row count query.
   */
  public <T> int findRowCount(OrmQueryRequest<T> request) {

    CQueryRowCount rcQuery = queryBuilder.buildRowCountQuery(request);
    try {

      int rowCount = rcQuery.findRowCount();

      if (request.isLogSql()) {
        String logSql = rcQuery.getGeneratedSql();
        if (TransactionManager.SQL_LOGGER.isTraceEnabled()) {
          logSql = Str.add(logSql, "; --bind(", rcQuery.getBindLog(), ")");
        }
        request.logSql(logSql);
      }

      if (request.isLogSummary()) {
        request.getTransaction().logSummary(rcQuery.getSummary());
      }

      if (request.getQuery().isFutureFetch()) {
        logger.debug("Future findRowCount completed!");
        request.getTransaction().end();
      }

      return rowCount;

    } catch (SQLException e) {
      throw CQuery.createPersistenceException(e, request.getTransaction(), rcQuery.getBindLog(), rcQuery.getGeneratedSql());
    }
  }

  /**
   * Read many beans using an iterator (except you need to close() the iterator
   * when you have finished).
   */
  public <T> QueryIterator<T> findIterate(OrmQueryRequest<T> request) {

    CQuery<T> cquery = queryBuilder.buildQuery(request);
    request.setCancelableQuery(cquery);

    try {

      if (!cquery.prepareBindExecuteQueryForwardOnly(forwardOnlyHintOnFindIterate)) {
        // query has been cancelled already
        logger.trace("Future fetch already cancelled");
        return null;
      }

      if (request.isLogSql()) {
        logSql(cquery);
      }

      // first check batch sizes set on query joins
      int iterateBufferSize = request.getSecondaryQueriesMinBatchSize(defaultSecondaryQueryBatchSize);
      if (iterateBufferSize < 1) {
        // not set on query joins so check if batch size set on query itself
        int queryBatch = request.getQuery().getLazyLoadBatchSize();
        if (queryBatch > 0) {
          iterateBufferSize = queryBatch;
        }
      }

      QueryIterator<T> readIterate = cquery.readIterate(iterateBufferSize, request);

      if (request.isLogSummary()) {
        logFindManySummary(cquery);
      }

      if (request.isAuditReads()) {
        // indicates we need to audit as the iterator progresses
        cquery.auditFindIterate();
      }

      return readIterate;

    } catch (SQLException e) {
      throw cquery.createPersistenceException(e);
    }
  }

  /**
   * Execute the find versions query returning version beans.
   */
  public <T> List<Version<T>> findVersions(OrmQueryRequest<T> request) {

    SpiQuery<T> query = request.getQuery();

    if (query.isVersionsBetween() && !historySupport.isBindAtFromClause()) {
      // just add as normal predicates using the lower bound
      query.where().gt(getSysPeriodLower(query), query.getVersionStart());
      query.where().lt(getSysPeriodLower(query), query.getVersionEnd());
    }

    // order by id asc, lower sys period desc
    query.orderBy().asc(request.getBeanDescriptor().getIdProperty().getName());
    query.orderBy().desc(getSysPeriodLower(query));

    CQuery<T> cquery = queryBuilder.buildQuery(request);
    try {
      cquery.prepareBindExecuteQuery();
      if (request.isLogSql()) {
        logSql(cquery);
      }

      List<Version<T>> versions = cquery.readVersions();
      deriveVersionDiffs(versions, request);

      if (request.isLogSummary()) {
        logFindManySummary(cquery);
      }

      if (request.isAuditReads()) {
        cquery.auditFindMany();
      }

      return versions;

    } catch (SQLException e) {
      throw cquery.createPersistenceException(e);

    } finally {
      if (cquery != null) {
        cquery.close();
      }
    }
  }

  private <T> void deriveVersionDiffs(List<Version<T>> versions, OrmQueryRequest<T> request) {

    BeanDescriptor<T> descriptor = request.getBeanDescriptor();

    if (!versions.isEmpty()) {
      Version<T> current = versions.get(0);
      if (versions.size() > 1) {
        for (int i = 1; i < versions.size(); i++) {
          Version<T> next = versions.get(i);
          deriveVersionDiff(current, next, descriptor);
          current = next;
        }
      }
      // put an empty map into the last one
      current.setDiff(new LinkedHashMap<String, ValuePair>());
    }
  }

  private <T> void deriveVersionDiff(Version<T> current, Version<T> prior, BeanDescriptor<T> descriptor) {

    Map<String, ValuePair> diff = DiffHelp.diff(current.getBean(), prior.getBean(), descriptor);
    current.setDiff(diff);
  }

  /**
   * Return the lower sys_period given the table alias of the query or default.
   */
  private <T> String getSysPeriodLower(SpiQuery<T> query) {
    String rootTableAlias = query.getAlias();
    if (rootTableAlias == null) {
      rootTableAlias = T0;
    }
    return historySupport.getSysPeriodLower(rootTableAlias);
  }

  /**
   * Find a list/map/set of beans.
   */
  public <T> BeanCollection<T> findMany(OrmQueryRequest<T> request) {


    SpiQuery<T> query = request.getQuery();
    if (query.getMaxRows() > 1 || query.getFirstRow() > 0) {
      // deemed to be a be a paging query - check that the order by contains
      // the id property to ensure unique row ordering for predicable paging
      request.getBeanDescriptor().appendOrderById(query);
    }

    CQuery<T> cquery = queryBuilder.buildQuery(request);
    request.setCancelableQuery(cquery);

    try {
      if (!cquery.prepareBindExecuteQuery()) {
        // query has been cancelled already
        logger.trace("Future fetch already cancelled");
        return null;
      }

      if (request.isLogSql()) {
        logSql(cquery);
      }

      BeanCollection<T> beanCollection = cquery.readCollection();
      if (request.isLogSummary()) {
        logFindManySummary(cquery);
      }

      if (request.isAuditReads()) {
        cquery.auditFindMany();
      }

      request.executeSecondaryQueries(false);

      return beanCollection;

    } catch (SQLException e) {
      throw cquery.createPersistenceException(e);

    } finally {
      if (cquery != null) {
        cquery.close();
      }
      if (query.isFutureFetch()) {
        // end the transaction for futureFindIds
        // as it had it's own transaction
        logger.debug("Future fetch completed!");
        request.getTransaction().end();
      }
    }
  }

  /**
   * Find and return a single bean using its unique id.
   */
  @SuppressWarnings("unchecked")
  public <T> T find(OrmQueryRequest<T> request) {

    EntityBean bean = null;

    CQuery<T> cquery = queryBuilder.buildQuery(request);

    try {
      cquery.prepareBindExecuteQuery();

      if (request.isLogSql()) {
        logSql(cquery);
      }

      if (cquery.readBean()) {
        bean = cquery.next();
      }

      if (request.isLogSummary()) {
        logFindBeanSummary(cquery);
      }

      if (request.isAuditReads()) {
        cquery.auditFind(bean);
      }

      request.executeSecondaryQueries(false);

      return (T) bean;

    } catch (SQLException e) {
      throw cquery.createPersistenceException(e);

    } finally {
      cquery.close();
    }
  }

  /**
   * Log the generated SQL to the transaction log.
   */
  private void logSql(CQuery<?> query) {

    String sql = query.getGeneratedSql();
    if (TransactionManager.SQL_LOGGER.isTraceEnabled()) {
      sql = Str.add(sql, "; --bind(", query.getBindLog(), ")");
    }
    query.getTransaction().logSql(sql);
  }

  /**
   * Log the FindById summary to the transaction log.
   */
  private void logFindBeanSummary(CQuery<?> q) {

    SpiQuery<?> query = q.getQueryRequest().getQuery();
    String loadMode = query.getLoadMode();
    String loadDesc = query.getLoadDescription();
    String lazyLoadProp = query.getLazyLoadProperty();
    ObjectGraphNode node = query.getParentNode();
    String originKey;
    if (node == null || node.getOriginQueryPoint() == null) {
      originKey = null;
    } else {
      originKey = node.getOriginQueryPoint().getKey();
    }

    StringBuilder msg = new StringBuilder(200);
    msg.append("FindBean ");
    if (loadMode != null) {
      msg.append("mode[").append(loadMode).append("] ");
    }
    msg.append("type[").append(q.getBeanName()).append("] ");
    if (query.isAutoTuned()) {
      msg.append("tuned[true] ");
    }
    if (query.isAsDraft()) {
      msg.append(" draft[true] ");
    }
    if (originKey != null) {
      msg.append("origin[").append(originKey).append("] ");
    }
    if (lazyLoadProp != null) {
      msg.append("lazyLoadProp[").append(lazyLoadProp).append("] ");
    }
    if (loadDesc != null) {
      msg.append("load[").append(loadDesc).append("] ");
    }
    msg.append("exeMicros[").append(q.getQueryExecutionTimeMicros());
    msg.append("] rows[").append(q.getLoadedRowDetail());
    msg.append("] bind[").append(q.getBindLog()).append("]");

    q.getTransaction().logSummary(msg.toString());
  }

  /**
   * Log the FindMany to the transaction log.
   */
  private void logFindManySummary(CQuery<?> q) {

    SpiQuery<?> query = q.getQueryRequest().getQuery();
    String loadMode = query.getLoadMode();
    String loadDesc = query.getLoadDescription();
    String lazyLoadProp = query.getLazyLoadProperty();
    ObjectGraphNode node = query.getParentNode();

    String originKey;
    if (node == null || node.getOriginQueryPoint() == null) {
      originKey = null;
    } else {
      originKey = node.getOriginQueryPoint().getKey();
    }

    StringBuilder msg = new StringBuilder(200);
    msg.append("FindMany ");
    if (loadMode != null) {
      msg.append("mode[").append(loadMode).append("] ");
    }
    msg.append("type[").append(q.getBeanName()).append("] ");
    if (query.isAutoTuned()) {
      msg.append("tuned[true] ");
    }
    if (query.isAsDraft()) {
      msg.append(" draft[true] ");
    }
    if (originKey != null) {
      msg.append("origin[").append(originKey).append("] ");
    }
    if (lazyLoadProp != null) {
      msg.append("lazyLoadProp[").append(lazyLoadProp).append("] ");
    }
    if (loadDesc != null) {
      msg.append("load[").append(loadDesc).append("] ");
    }
    msg.append("exeMicros[").append(q.getQueryExecutionTimeMicros());
    msg.append("] rows[").append(q.getLoadedRowDetail());
    msg.append("] predicates[").append(q.getLogWhereSql());
    msg.append("] bind[").append(q.getBindLog()).append("]");

    q.getTransaction().logSummary(msg.toString());
  }
}
