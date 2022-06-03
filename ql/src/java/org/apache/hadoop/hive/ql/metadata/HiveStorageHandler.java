/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.metadata;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.hive.common.classification.InterfaceAudience;
import org.apache.hadoop.hive.common.classification.InterfaceStability;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaHook;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.LockType;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.ql.Context.Operation;
import org.apache.hadoop.hive.ql.ddl.table.AlterTableType;
import org.apache.hadoop.hive.ql.hooks.WriteEntity;
import org.apache.hadoop.hive.ql.parse.PartitionTransformSpec;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.plan.DynamicPartitionCtx;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;
import org.apache.hadoop.hive.ql.plan.FileSinkDesc;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;
import org.apache.hadoop.hive.ql.plan.TableDesc;
import org.apache.hadoop.hive.ql.security.authorization.HiveAuthorizationProvider;
import org.apache.hadoop.hive.ql.security.authorization.HiveCustomStorageHandlerUtils;
import org.apache.hadoop.hive.ql.stats.Partish;
import org.apache.hadoop.hive.serde2.AbstractSerDe;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputFormat;

import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * HiveStorageHandler defines a pluggable interface for adding
 * new storage handlers to Hive.  A storage handler consists of
 * a bundle of the following:
 *
 *<ul>
 *<li>input format
 *<li>output format
 *<li>serde
 *<li>metadata hooks for keeping an external catalog in sync
 * with Hive's metastore
 *<li>rules for setting up the configuration properties on
 * map/reduce jobs which access tables stored by this handler
 *</ul>
 *
 * Storage handler classes are plugged in using the STORED BY 'classname'
 * clause in CREATE TABLE.
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public interface HiveStorageHandler extends Configurable {

  List<AlterTableType> DEFAULT_ALLOWED_ALTER_OPS = ImmutableList.of(
      AlterTableType.ADDPROPS, AlterTableType.DROPPROPS, AlterTableType.ADDCOLS);

  /**
   * @return Class providing an implementation of {@link InputFormat}
   */
  public Class<? extends InputFormat> getInputFormatClass();

  /**
   * @return Class providing an implementation of {@link OutputFormat}
   */
  public Class<? extends OutputFormat> getOutputFormatClass();

  /**
   * @return Class providing an implementation of {@link AbstractSerDe}
   */
  public Class<? extends AbstractSerDe> getSerDeClass();

  /**
   * @return metadata hook implementation, or null if this
   * storage handler does not need any metadata notifications
   */
  public HiveMetaHook getMetaHook();

  /**
   * Returns the implementation specific authorization provider
   *
   * @return authorization provider
   * @throws HiveException
   */
  public HiveAuthorizationProvider getAuthorizationProvider()
    throws HiveException;

  /**
   * This method is called to allow the StorageHandlers the chance
   * to populate the JobContext.getConfiguration() with properties that
   * maybe be needed by the handler's bundled artifacts (ie InputFormat, SerDe, etc).
   * Key value pairs passed into jobProperties are guaranteed to be set in the job's
   * configuration object. User's can retrieve "context" information from tableDesc.
   * User's should avoid mutating tableDesc and only make changes in jobProperties.
   * This method is expected to be idempotent such that a job called with the
   * same tableDesc values should return the same key-value pairs in jobProperties.
   * Any external state set by this method should remain the same if this method is
   * called again. It is up to the user to determine how best guarantee this invariant.
   *
   * This method in particular is to create a configuration for input.
   * @param tableDesc descriptor for the table being accessed
   * @param jobProperties receives properties copied or transformed
   * from the table properties
   */
  public abstract void configureInputJobProperties(TableDesc tableDesc,
    Map<String, String> jobProperties);

  /**
   * This method is called to allow the StorageHandlers the chance to
   * populate secret keys into the job's credentials.
   */
  public abstract void configureInputJobCredentials(TableDesc tableDesc, Map<String, String> secrets);

  /**
   * This method is called to allow the StorageHandlers the chance
   * to populate the JobContext.getConfiguration() with properties that
   * maybe be needed by the handler's bundled artifacts (ie InputFormat, SerDe, etc).
   * Key value pairs passed into jobProperties are guaranteed to be set in the job's
   * configuration object. User's can retrieve "context" information from tableDesc.
   * User's should avoid mutating tableDesc and only make changes in jobProperties.
   * This method is expected to be idempotent such that a job called with the
   * same tableDesc values should return the same key-value pairs in jobProperties.
   * Any external state set by this method should remain the same if this method is
   * called again. It is up to the user to determine how best guarantee this invariant.
   *
   * This method in particular is to create a configuration for output.
   * @param tableDesc descriptor for the table being accessed
   * @param jobProperties receives properties copied or transformed
   * from the table properties
   */
  public abstract void configureOutputJobProperties(TableDesc tableDesc,
    Map<String, String> jobProperties);

  /**
   * Deprecated use configureInputJobProperties/configureOutputJobProperties
   * methods instead.
   *
   * Configures properties for a job based on the definition of the
   * source or target table it accesses.
   *
   * @param tableDesc descriptor for the table being accessed
   *
   * @param jobProperties receives properties copied or transformed
   * from the table properties
   */
  @Deprecated
  public void configureTableJobProperties(
    TableDesc tableDesc,
    Map<String, String> jobProperties);

  /**
   * Called just before submitting MapReduce job.
   *
   * @param tableDesc descriptor for the table being accessed
   * @param jobConf jobConf for MapReduce job
   */
  public void configureJobConf(TableDesc tableDesc, JobConf jobConf);

  /**
   * Used to fetch runtime information about storage handler during DESCRIBE EXTENDED statement
   *
   * @param table table definition
   * @return StorageHandlerInfo containing runtime information about storage handler
   * OR `null` if the storage handler choose to not provide any runtime information.
   */
  public default StorageHandlerInfo getStorageHandlerInfo(Table table) throws MetaException
  {
    return null;
  }

  default LockType getLockType(WriteEntity writeEntity){
    return LockType.EXCLUSIVE;
  }

  /**
   * Test if the storage handler allows the push-down of join filter predicate to prune further the splits.
   *
   * @param table The table to filter.
   * @param syntheticFilterPredicate Join filter predicate.
   * @return true if supports dynamic split pruning for the given predicate.
   */

  default boolean addDynamicSplitPruningEdge(org.apache.hadoop.hive.ql.metadata.Table table,
      ExprNodeDesc syntheticFilterPredicate) {
    return false;
  }

  /**
   * Used to add additional operator specific information from storage handler during DESCRIBE EXTENDED statement.
   *
   * @param operatorDesc operatorDesc
   * @param initialProps Map containing initial operator properties
   * @return Map&lt;String, String&gt; containing additional operator specific information from storage handler
   * OR `initialProps` if the storage handler choose to not provide any such information.
   */
  default Map<String, String> getOperatorDescProperties(OperatorDesc operatorDesc, Map<String, String> initialProps) {
    return initialProps;
  }

  /**
   * Return some basic statistics (numRows, numFiles, totalSize) calculated by the underlying storage handler
   * implementation.
   * @param partish a partish wrapper class
   * @return map of basic statistics, can be null
   */
  default Map<String, String> getBasicStatistics(Partish partish) {
    return null;
  }

  /**
   * Check if the storage handler can provide basic statistics.
   * @return true if the storage handler can supply the basic statistics
   */
  default boolean canProvideBasicStatistics() {
    return false;
  }

  /**
   * Check if CTAS operations should behave in a direct-insert manner (i.e. no move task).
   *
   * If true, the compiler will not include the table creation task and move task into the execution plan.
   * Instead, it's the responsibility of storage handler/serde to create the table during the compilation phase.
   * Please note that the atomicity of the operation will suffer in this case, i.e. the created table might become
   * exposed, depending on the implementation, before the CTAS operations finishes.
   * Rollback (e.g. dropping the table) is also the responsibility of the storage handler in case of failures.
   *
   * @return whether direct insert CTAS is required
   */
  default boolean directInsertCTAS() {
    return false;
  }

  /**
   * Check if partition columns should be removed and added to the list of regular columns in HMS.
   * This can be useful for non-native tables where the table format/layout differs from the standard Hive table layout,
   * e.g. Iceberg tables. For these table formats, the partition column values are stored in the data files along with
   * regular column values, therefore the object inspectors should include the partition columns as well.
   * Any partitioning scheme provided via the standard HiveQL syntax will be honored but stored in someplace
   * other than HMS, depending on the storage handler implementation.
   *
   * @return whether table should always be unpartitioned from the perspective of HMS
   */
  default boolean alwaysUnpartitioned() {
    return false;
  }

  enum AcidSupportType {
    NONE,
    WITH_TRANSACTIONS,
    WITHOUT_TRANSACTIONS
  }

  /**
   * Specifies whether the table supports ACID operations or not (DELETE, UPDATE and MERGE statements).
   *
   * Possible return values:
   * <ul>
   *   <li>AcidSupportType.NONE - ACID operations are not supported</li>
   *   <li>AcidSupportType.WITH_TRANSACTIONS - ACID operations are supported, and must use a valid HiveTxnManager to wrap
   *   the operation in a transaction, like in the case of standard Hive ACID tables</li>
   *   <li>AcidSupportType.WITHOUT_TRANSACTIONS - ACID operations are supported, and there is no need for a HiveTxnManager
   *   to open/close transactions for the operation, i.e. org.apache.hadoop.hive.ql.lockmgr.DummyTxnManager
   *   can be used</li>
   * </ul>
   *
   * @return the table's ACID support type
   */
  default AcidSupportType supportsAcidOperations() {
    return AcidSupportType.NONE;
  }

  /**
   * Specifies which additional virtual columns should be added to the virtual column registry during compilation
   * for tables that support ACID operations.
   *
   * Should only return a non-empty list if {@link HiveStorageHandler#supportsAcidOperations()} ()} returns something
   * other NONE.
   *
   * @return the list of ACID virtual columns
   */
  default List<VirtualColumn> acidVirtualColumns() {
    return Collections.emptyList();
  }

  /**
   * {@link org.apache.hadoop.hive.ql.parse.UpdateDeleteSemanticAnalyzer} rewrites DELETE/UPDATE queries into INSERT
   * queries.
   * - DELETE FROM T WHERE A = 32 is rewritten into
   * INSERT INTO T SELECT &lt;selectCols&gt; FROM T WHERE A = 32 SORT BY &lt;sortCols&gt;.
   * - UPDATE T SET B=12 WHERE A = 32 is rewritten into
   * INSERT INTO T SELECT &lt;selectCols&gt;, &lt;newValues&gt; FROM T WHERE A = 32 SORT BY &lt;sortCols&gt;.
   *
   * This method specifies which columns should be injected into the &lt;selectCols&gt; part of the rewritten query.
   *
   * Should only return a non-empty list if {@link HiveStorageHandler#supportsAcidOperations()} returns something
   * other NONE.
   *
   * @param table the table which is being deleted/updated/merged into
   * @param operation the operation type we are executing
   * @return the list of columns that should be projected in the rewritten ACID query
   */
  default List<FieldSchema> acidSelectColumns(org.apache.hadoop.hive.ql.metadata.Table table, Operation operation) {
    return Collections.emptyList();
  }

  /**
   * {@link org.apache.hadoop.hive.ql.parse.UpdateDeleteSemanticAnalyzer} rewrites DELETE/UPDATE queries into INSERT
   * queries. E.g. DELETE FROM T WHERE A = 32 is rewritten into
   * INSERT INTO T SELECT &lt;selectCols&gt; FROM T WHERE A = 32 SORT BY &lt;sortCols&gt;.
   *
   * This method specifies which columns should be injected into the &lt;sortCols&gt; part of the rewritten query.
   *
   * Should only return a non-empty list if {@link HiveStorageHandler#supportsAcidOperations()} returns something
   * other NONE.
   *
   * @param table the table which is being deleted/updated/merged into
   * @param operation the operation type we are executing
   * @return the list of columns that should be used as sort columns in the rewritten ACID query
   */
  default List<FieldSchema> acidSortColumns(org.apache.hadoop.hive.ql.metadata.Table table, Operation operation) {
    return Collections.emptyList();
  }

  /**
   * Check if the underlying storage handler implementation support partition transformations.
   * @return true if the storage handler can support it
   */
  default boolean supportsPartitionTransform() {
    return false;
  }

  /**
   * Return a list of partition transform specifications. This method should be overwritten in case
   * {@link HiveStorageHandler#supportsPartitionTransform()} returns true.
   * @param table the HMS table, must be non-null
   * @return partition transform specification, can be null.
   */
  default List<PartitionTransformSpec> getPartitionTransformSpec(org.apache.hadoop.hive.ql.metadata.Table table) {
    return null;
  }

  /**
   * Creates a DynamicPartitionCtx instance that will be set up by the storage handler itself. Useful for non-native
   * tables where partitions are not handled by Hive, and sorting is required in a custom way before writing the table.
   * @param conf job conf
   * @param table the HMS table
   * @return the created DP context object, null if DP context / sorting is not required
   * @throws SemanticException
   */
  default DynamicPartitionCtx createDPContext(
          HiveConf conf, org.apache.hadoop.hive.ql.metadata.Table table, Operation writeOperation)
      throws SemanticException {
    Preconditions.checkState(alwaysUnpartitioned(), "Should only be called for table formats where partitioning " +
        "is not handled by Hive but the table format itself. See alwaysUnpartitioned() method.");
    return null;
  }

  /**
   * Get file format property key, if the file format is configured through a table property.
   * @return table property key, can be null
   */
  default String getFileFormatPropertyKey() {
    return null;
  }

  /**
   * Checks if we should keep the {@link org.apache.hadoop.hive.ql.exec.MoveTask} and use the
   * {@link #storageHandlerCommit(Properties, boolean)} method for committing inserts instead of
   * {@link org.apache.hadoop.hive.metastore.DefaultHiveMetaHook#commitInsertTable(Table, boolean)}.
   * @return Returns true if we should use the {@link #storageHandlerCommit(Properties, boolean)} method
   */
  default boolean commitInMoveTask() {
    return false;
  }

  /**
   * Commits the inserts for the non-native tables. Used in the {@link org.apache.hadoop.hive.ql.exec.MoveTask}.
   * @param commitProperties Commit properties which are needed for the handler based commit
   * @param overwrite If this is an INSERT OVERWRITE then it is true
   * @throws HiveException If there is an error during commit
   */
  default void storageHandlerCommit(Properties commitProperties, boolean overwrite) throws HiveException {
    throw new UnsupportedOperationException();
  }

  /**
   * Checks whether a certain ALTER TABLE operation is supported by the storage handler implementation.
   *
   * @param opType The alter operation type (e.g. RENAME_COLUMNS)
   * @return whether the operation is supported by the storage handler
   */
  default boolean isAllowedAlterOperation(AlterTableType opType) {
    return DEFAULT_ALLOWED_ALTER_OPS.contains(opType);
  }

  /**
   * Check if the underlying storage handler implementation supports truncate operation
   * for non native tables.
   * @return true if the storage handler can support it
   * @return
   */
  default boolean supportsTruncateOnNonNativeTables() {
    return false;
  }

  /**
   * Should return true if the StorageHandler is able to handle time travel.
   * @return True if time travel is allowed
   */
  default boolean isTimeTravelAllowed() {
    return false;
  }

  default boolean isMetadataTableSupported() {
    return false;
  }

  default boolean isValidMetadataTable(String metaTableName) {
    return false;
  }

  /**
   * Constructs a URI for authorization purposes using the HMS table object
   * @param table The HMS table object
   * @return the URI for authorization
   */
  default URI getURIForAuth(Table table) throws URISyntaxException {
    Map<String, String> tableProperties = HiveCustomStorageHandlerUtils.getTableProperties(table);
    return new URI(this.getClass().getSimpleName().toLowerCase() + "://" +
        HiveCustomStorageHandlerUtils.getTablePropsForCustomStorageHandler(tableProperties));
  }

  /**
   * Validates whether the sink operation is permitted for the specific storage handler, based
   * on information contained in the sinkDesc.
   * @param sinkDesc The sink descriptor
   * @throws SemanticException if the sink operation is not allowed
   */
  default void validateSinkDesc(FileSinkDesc sinkDesc) throws SemanticException {
  }
}
