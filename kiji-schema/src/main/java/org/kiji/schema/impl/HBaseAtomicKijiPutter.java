/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.schema.impl;

import java.io.IOException;
import java.util.ArrayList;

import com.google.common.base.Preconditions;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.annotations.ApiAudience;
import org.kiji.schema.AtomicKijiPutter;
import org.kiji.schema.EntityId;
import org.kiji.schema.KijiCellEncoder;
import org.kiji.schema.KijiColumnName;
import org.kiji.schema.hbase.HBaseColumnName;
import org.kiji.schema.layout.impl.CellEncoderProvider;
import org.kiji.schema.layout.impl.ColumnNameTranslator;

/**
 * HBase implementation of AtomicKijiPutter.
 *
 * Access via HBaseKijiWriterFactory.openAtomicKijiPutter(), facilitates guaranteed atomic
 * puts in batch on a single row.
 *
 * Use <code>begin(EntityId)</code> to open a new transaction,
 * <code>put(family, qualifier, value)</code> to stage a put in the transaction,
 * and <code>commit()</code> or <code>checkAndCommit(family, qualifier, value)</code>
 * to write all staged puts atomically.
 *
 * This class is not thread-safe.  It is the user's responsibility to protect against
 * concurrent access to a writer while a transaction is being constructed.
 */
@ApiAudience.Private
public final class HBaseAtomicKijiPutter implements AtomicKijiPutter {
  private static final Logger LOG = LoggerFactory.getLogger(HBaseAtomicKijiPutter.class);

  /** The Kiji table instance. */
  private final HBaseKijiTable mTable;

  /** The HTableInterface associated with the KijiTable. */
  private final HTableInterface mHTable;

  /** HBase column name translator. */
  private final ColumnNameTranslator mTranslator;

  /** Provider for cell encoders. */
  private final CellEncoderProvider mCellEncoderProvider;

  /** EntityId of the row to mutate atomically. */
  private EntityId mEntityId;

  /** HBaseRowKey of the row to mutate. */
  private byte[] mId;

  /** List of HBase KeyValue objects to be written. */
  private ArrayList<KeyValue> mHopper = null;

  /**
   * Composite Put containing batch puts.
   * mPut is null outside of a begin() — commit()/rollback() transaction.
   * mPut is non null inside of a begin() — commit()/rollback() transaction.
   */
  private Put mPut = null;

  /**
   * Constructor for this AtomicKijiPutter.
   *
   * @param table The HBaseKijiTable to which this writer writes.
   * @throws IOException in case of an error.
   */
  public HBaseAtomicKijiPutter(HBaseKijiTable table) throws IOException {
    mTable = table;
    mTranslator = new ColumnNameTranslator(mTable.getLayout());
    mHTable = HBaseKijiTable.createHTableInterface(mTable);
    mCellEncoderProvider =
        new CellEncoderProvider(mTable, DefaultKijiCellEncoderFactory.get());

    // Retain the table only when everything succeeds.
    table.retain();
  }

  /** Resets the current transaction. */
  private void reset() {
    mPut = null;
    mEntityId = null;
    mHopper = null;
    mId = null;
  }

  /** {@inheritDoc} */
  @Override
  public void begin(EntityId eid) {
    // Preconditions.checkArgument() cannot be used here because mEntityId is null between calls to
    // begin().
    if (mPut != null) {
      throw new IllegalStateException(String.format("There is already a transaction in progress on "
          + "row: %s. Call commit(), checkAndCommit(), or rollback() to clear the Put.",
          mEntityId.toShellString()));
    }
    mEntityId = eid;
    mId = eid.getHBaseRowKey();
    mHopper = new ArrayList<KeyValue>();
    mPut = new Put(mId);
  }

  /** {@inheritDoc} */
  @Override
  public EntityId getEntityId() {
    return mEntityId;
  }

  /** {@inheritDoc} */
  @Override
  public void commit() throws IOException {
    Preconditions.checkState(mPut != null, "commit() must be paired with a call to begin()");
    for (KeyValue kv : mHopper) {
      mPut.add(kv);
    }

    mHTable.put(mPut);
    if (!mHTable.isAutoFlush()) {
      mHTable.flushCommits();
    }
    reset();
  }

  /** {@inheritDoc} */
  @Override
  public <T> boolean checkAndCommit(String family, String qualifier, T value) throws IOException {
    Preconditions.checkState(mPut != null,
        "checkAndCommit() must be paired with a call to begin()");
    final KijiColumnName kijiColumnName = new KijiColumnName(family, qualifier);
    final HBaseColumnName columnName = mTranslator.toHBaseColumnName(kijiColumnName);

    final KijiCellEncoder cellEncoder = mCellEncoderProvider.getEncoder(family, qualifier);
    final byte[] encoded = cellEncoder.encode(value);

    for (KeyValue kv : mHopper) {
      mPut.add(kv);
    }
    boolean retVal = mHTable.checkAndPut(
        mId, columnName.getFamily(), columnName.getQualifier(), encoded, mPut);
    if (retVal) {
      if (!mHTable.isAutoFlush()) {
        mHTable.flushCommits();
      }
      reset();
    }
    return retVal;
  }

  /** {@inheritDoc} */
  @Override
  public void rollback() {
    Preconditions.checkState(mPut != null, "rollback() must be paired with a call to begin()");
    reset();
  }

  /** {@inheritDoc} */
  @Override
  public <T> void put(String family, String qualifier, T value) throws IOException {
    Preconditions.checkState(mPut != null, "calls to put() must be between calls to begin() and "
        + "commit(), checkAndCommit(), or rollback()");
    put(family, qualifier, HConstants.LATEST_TIMESTAMP, value);
  }

  /** {@inheritDoc} */
  @Override
  public <T> void put(String family, String qualifier, long timestamp, T value) throws IOException {
    Preconditions.checkState(mPut != null, "calls to put() must be between calls to begin() and "
        + "commit(), checkAndCommit(), or rollback()");
    final KijiColumnName kijiColumnName = new KijiColumnName(family, qualifier);
    final HBaseColumnName columnName = mTranslator.toHBaseColumnName(kijiColumnName);

    final KijiCellEncoder cellEncoder = mCellEncoderProvider.getEncoder(family, qualifier);
    final byte[] encoded = cellEncoder.encode(value);

    mHopper.add(new KeyValue(
        mId, columnName.getFamily(), columnName.getQualifier(), timestamp, encoded));
  }

  /** {@inheritDoc} */
  @Override
  public void close() throws IOException {
    if (mPut != null) {
      LOG.warn("Closing HBaseAtomicKijiPutter "
          + "while a transaction on table {} on entity ID {} is in progress. "
          + "Rolling back transaction.",
          mEntityId);
      rollback();
    }
    mHTable.close();
    mTable.release();
  }
}
