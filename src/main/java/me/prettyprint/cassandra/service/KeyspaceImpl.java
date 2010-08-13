package me.prettyprint.cassandra.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.prettyprint.cassandra.extractors.StringExtractor;
import me.prettyprint.cassandra.model.Extractor;
import me.prettyprint.cassandra.model.HectorException;
import me.prettyprint.cassandra.model.HectorTransportException;
import me.prettyprint.cassandra.model.InvalidRequestException;
import me.prettyprint.cassandra.service.CassandraClient.FailoverPolicy;

import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.Clock;
import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ColumnOrSuperColumn;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.thrift.ColumnPath;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.KeyRange;
import org.apache.cassandra.thrift.KeySlice;
import org.apache.cassandra.thrift.Mutation;
import org.apache.cassandra.thrift.NotFoundException;
import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.cassandra.thrift.SliceRange;
import org.apache.cassandra.thrift.SuperColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of a Keyspace
 *
 * @author Ran Tavory (rantav@gmail.com)
 *
 */
/* package */class KeyspaceImpl implements Keyspace {

  private static final Logger log = LoggerFactory.getLogger(KeyspaceImpl.class);

  private CassandraClient client;

  private final String keyspaceName;

  private final Map<String, Map<String, String>> keyspaceDesc;

  private final ConsistencyLevel consistency;

  private final FailoverPolicy failoverPolicy;

  private final CassandraClientPool clientPools;

  private final CassandraClientMonitor monitor;

  private final ExceptionsTranslator xtrans;

  public KeyspaceImpl(CassandraClient client, String keyspaceName,
      Map<String, Map<String, String>> keyspaceDesc, ConsistencyLevel consistencyLevel,
      FailoverPolicy failoverPolicy, CassandraClientPool clientPools, CassandraClientMonitor monitor)
      throws HectorTransportException {
    this.client = client;
    this.consistency = consistencyLevel;
    this.keyspaceDesc = keyspaceDesc;
    this.keyspaceName = keyspaceName;
    this.failoverPolicy = failoverPolicy;
    this.clientPools = clientPools;
    this.monitor = monitor;
    xtrans = new ExceptionsTranslatorImpl();
  }


  public <K> void batchMutate(final Map<K,Map<String,List<Mutation>>> mutationMap, final Extractor<K> keyExtractor) 
      throws HectorException {

    Operation<Void> op = new Operation<Void>(OperationType.WRITE) {
    
      public Void execute(Cassandra.Client cassandra) throws HectorException {
        try {
          cassandra.batch_mutate(keyExtractor.toBytesMap(mutationMap), consistency);
        } catch (Exception e) {
          throw xtrans.translate(e);
        }
        return null;
      }
    };
    operateWithFailover(op);
  }


  public <K> void batchMutate(BatchMutation<K> batchMutation) throws HectorException {
    batchMutate(batchMutation.getMutationMap(), batchMutation.getKeyExtractor());
  }


  public <K> int getCount(final K key, final ColumnParent columnParent, final SlicePredicate predicate, final Extractor<K> keyExtractor) throws HectorException {
    Operation<Integer> op = new Operation<Integer>(OperationType.READ) {
    
      public Integer execute(Cassandra.Client cassandra) throws HectorException {
        try {
          return cassandra.get_count(keyExtractor.toBytes(key), columnParent, predicate, consistency);
        } catch (Exception e) {
          throw xtrans.translate(e);
        }
      }
    };
    operateWithFailover(op);
    return op.getResult();
  }

  private void operateWithFailover(Operation<?> op) throws HectorException {
    FailoverOperator operator = new FailoverOperator(failoverPolicy, monitor, client,
        clientPools, this);
    client = operator.operate(op);
  }



  public <K> LinkedHashMap<K, List<Column>> getRangeSlices(final ColumnParent columnParent,
      final SlicePredicate predicate, final KeyRange keyRange, final Extractor<K> keyExtractor) throws HectorException {
    Operation<LinkedHashMap<K, List<Column>>> op = new Operation<LinkedHashMap<K, List<Column>>>(
        OperationType.READ) {
    
      public LinkedHashMap<K, List<Column>> execute(Cassandra.Client cassandra)
          throws HectorException {
        try {
          List<KeySlice> keySlices = cassandra.get_range_slices(columnParent,
              predicate, keyRange, consistency);
          if (keySlices == null || keySlices.isEmpty()) {
            return new LinkedHashMap<K, List<Column>>(0);
          }
          LinkedHashMap<K, List<Column>> ret = new LinkedHashMap<K, List<Column>>(
              keySlices.size());
          for (KeySlice keySlice : keySlices) {
            ret.put(keyExtractor.fromBytes(keySlice.getKey()), getColumnList(keySlice.getColumns()));
          }
          return ret;
        } catch (Exception e) {
          throw xtrans.translate(e);
        }
      };
    };
    operateWithFailover(op);
    return op.getResult();
  }



  public <K> LinkedHashMap<K, List<SuperColumn>> getSuperRangeSlices(
      final ColumnParent columnParent, final SlicePredicate predicate, final KeyRange keyRange, final Extractor<K> keyExtractor)
      throws HectorException {
    Operation<LinkedHashMap<K, List<SuperColumn>>> op = new Operation<LinkedHashMap<K, List<SuperColumn>>>(
        OperationType.READ) {
    
      public LinkedHashMap<K, List<SuperColumn>> execute(Cassandra.Client cassandra)
          throws HectorException {
        try {
          List<KeySlice> keySlices = cassandra.get_range_slices(columnParent,
              predicate, keyRange, consistency);
          if (keySlices == null || keySlices.isEmpty()) {
            return new LinkedHashMap<K, List<SuperColumn>>();
          }
          LinkedHashMap<K, List<SuperColumn>> ret = new LinkedHashMap<K, List<SuperColumn>>(
              keySlices.size());
          for (KeySlice keySlice : keySlices) {
            ret.put(keyExtractor.fromBytes(keySlice.getKey()), getSuperColumnList(keySlice.getColumns()));
          }
          return ret;
        } catch (Exception e) {
          throw xtrans.translate(e);
        }
      }
    };
    operateWithFailover(op);
    return op.getResult();
  }


  public <K> List<Column> getSlice(final K key, final ColumnParent columnParent,
      final SlicePredicate predicate, final Extractor<K> keyExtractor) throws HectorException {
    Operation<List<Column>> op = new Operation<List<Column>>(OperationType.READ) {
    
      public List<Column> execute(Cassandra.Client cassandra) throws HectorException {
        try {
          List<ColumnOrSuperColumn> cosclist = cassandra.get_slice(keyExtractor.toBytes(key), columnParent,
              predicate, consistency);

          if (cosclist == null) {
            return null;
          }
          ArrayList<Column> result = new ArrayList<Column>(cosclist.size());
          for (ColumnOrSuperColumn cosc : cosclist) {
            result.add(cosc.getColumn());
          }
          return result;
        } catch (Exception e) {
          throw xtrans.translate(e);
        }
      }
    };
    operateWithFailover(op);
    return op.getResult();
  }

  public List<Column> getSlice(String key, ColumnParent columnParent, SlicePredicate predicate)
  throws HectorException {
    return getSlice(key, columnParent, predicate, StringExtractor.get());
  }

  public <K> SuperColumn getSuperColumn(final K key, final ColumnPath columnPath, final Extractor<K> keyExtractor) throws HectorException {
    valideColumnPath(columnPath);

    Operation<SuperColumn> op = new Operation<SuperColumn>(OperationType.READ) {
    
      public SuperColumn execute(Cassandra.Client cassandra) throws HectorException {
        ColumnOrSuperColumn cosc;
        try {
          cosc = cassandra.get(keyExtractor.toBytes(key), columnPath, consistency);
        } catch (NotFoundException e) {
          setException(xtrans.translate(e));
          return null;
        } catch (Exception e) {
          throw xtrans.translate(e);
        }
        return cosc == null ? null : cosc.getSuper_column();
      }

    };
    operateWithFailover(op);
    if (op.hasException()) {
      throw op.getException();
    }
    return op.getResult();
  }
  
  public List<SuperColumn> getSuperSlice(String key, ColumnParent columnParent,
        SlicePredicate predicate) throws HectorException {
    return getSuperSlice(key, columnParent, predicate, StringExtractor.get());
  }


  public <K> SuperColumn getSuperColumn(final K key, final ColumnPath columnPath,
      final boolean reversed, final int size, final Extractor<K> keyExtractor) throws HectorException {
    valideSuperColumnPath(columnPath);
    final SliceRange sliceRange = new SliceRange(new byte[0], new byte[0], reversed, size);
    Operation<SuperColumn> op = new Operation<SuperColumn>(OperationType.READ) {
    
      public SuperColumn execute(Cassandra.Client cassandra) throws HectorException {
        ColumnParent clp = new ColumnParent(columnPath.getColumn_family());
        clp.setSuper_column(columnPath.getSuper_column());

        SlicePredicate sp = new SlicePredicate();
        sp.setSlice_range(sliceRange);

        try {
          List<ColumnOrSuperColumn> cosc = cassandra.get_slice(keyExtractor.toBytes(key), clp, sp,
              consistency);
          if (cosc == null || cosc.isEmpty()) {
            return null;
          }
          return new SuperColumn(columnPath.getSuper_column(), getColumnList(cosc));
        } catch (Exception e) {
          throw xtrans.translate(e);
        }
      }
    };
    operateWithFailover(op);
    return op.getResult();
  }
  
  public SuperColumn getSuperColumn(String key, ColumnPath columnPath) throws HectorException {
    return getSuperColumn(key, columnPath, StringExtractor.get());
  }


  public <K> List<SuperColumn> getSuperSlice(final K key, final ColumnParent columnParent,
      final SlicePredicate predicate, final Extractor<K> keyExtractor) throws HectorException {
    Operation<List<SuperColumn>> op = new Operation<List<SuperColumn>>(OperationType.READ) {
    
      public List<SuperColumn> execute(Cassandra.Client cassandra) throws HectorException {
        try {
          List<ColumnOrSuperColumn> cosclist = cassandra.get_slice(keyExtractor.toBytes(key), columnParent,
              predicate, consistency);
          if (cosclist == null) {
            return null;
          }
          ArrayList<SuperColumn> result = new ArrayList<SuperColumn>(cosclist.size());
          for (ColumnOrSuperColumn cosc : cosclist) {
            result.add(cosc.getSuper_column());
          }
          return result;
        } catch (Exception e) {
          throw xtrans.translate(e);
        }
      }
    };
    operateWithFailover(op);
    return op.getResult();
  }



  public <K> void insert(final K key, final ColumnParent columnParent, final Column column, final Extractor<K> keyExtractor) throws HectorException {
    Operation<Void> op = new Operation<Void>(OperationType.WRITE) {
    
      public Void execute(Cassandra.Client cassandra) throws HectorException {
        try {
          cassandra.insert(keyExtractor.toBytes(key), columnParent, column, consistency);
          return null;
        } catch (Exception e) {
          throw xtrans.translate(e);
        }
      }
    };
    operateWithFailover(op);
  }

  public void insert(String key, ColumnPath columnPath, byte[] value) throws HectorException {
    ColumnParent columnParent = new ColumnParent(columnPath.getColumn_family());
    Column column = new Column(columnPath.getColumn(), value, createClock());
    insert(key, columnParent, column, StringExtractor.get());
  }

  public void insert(String key, ColumnPath columnPath, byte[] value, long timestamp) throws HectorException {
    ColumnParent columnParent = new ColumnParent(columnPath.getColumn_family());
    Column column = new Column(columnPath.getColumn(), value, new Clock(timestamp));
    insert(key, columnParent, column, StringExtractor.get());
  }


  public <K> Map<K, List<Column>> multigetSlice(final List<K> keys,
      final ColumnParent columnParent, final SlicePredicate predicate, final Extractor<K> keyExtractor) throws HectorException {
    Operation<Map<K, List<Column>>> getCount = new Operation<Map<K, List<Column>>>(
        OperationType.READ) {
    
      public Map<K, List<Column>> execute(Cassandra.Client cassandra) throws HectorException {
        try {
          
          List<byte[]> byte_keys = keyExtractor.toBytesList(keys);
                    
          Map<byte[], List<ColumnOrSuperColumn>> cfmap = cassandra.multiget_slice(
              byte_keys, columnParent, predicate, consistency);

          Map<K, List<Column>> result = new HashMap<K, List<Column>>();
          for (Map.Entry<byte[], List<ColumnOrSuperColumn>> entry : cfmap.entrySet()) {
            result.put(keyExtractor.fromBytes(entry.getKey()), getColumnList(entry.getValue()));
          }
          return result;
        } catch (Exception e) {
          throw xtrans.translate(e);
        }
      }
    };
    operateWithFailover(getCount);
    return getCount.getResult();

  }


  public <K> Map<K, SuperColumn> multigetSuperColumn(List<K> keys, ColumnPath columnPath, Extractor<K> keyExtractor)
      throws HectorException {
    return multigetSuperColumn(keys, columnPath, false, Integer.MAX_VALUE, keyExtractor);
  }


  public <K> Map<K, SuperColumn> multigetSuperColumn(List<K> keys, ColumnPath columnPath,
      boolean reversed, int size, Extractor<K> keyExtractor) throws HectorException {
    valideSuperColumnPath(columnPath);

    // only can get supercolumn by multigetSuperSlice
    ColumnParent clp = new ColumnParent(columnPath.getColumn_family());
    clp.setSuper_column(columnPath.getSuper_column());

    SliceRange sr = new SliceRange(new byte[0], new byte[0], reversed, size);
    SlicePredicate sp = new SlicePredicate();
    sp.setSlice_range(sr);

    Map<K, List<SuperColumn>> sclist = multigetSuperSlice(keys, clp, sp, keyExtractor);

    if (sclist == null || sclist.isEmpty()) {
      return Collections.emptyMap();
    }

    Map<K, SuperColumn> result = new HashMap<K, SuperColumn>(keys.size() * 2);
    for (Map.Entry<K, List<SuperColumn>> entry : sclist.entrySet()) {
      List<SuperColumn> sclistByKey = entry.getValue();
      if (sclistByKey.size() > 0) {
        result.put(entry.getKey(), sclistByKey.get(0));
      }
    }
    return result;
  }


  public <K> Map<K, List<SuperColumn>> multigetSuperSlice(final List<K> keys,
      final ColumnParent columnParent, final SlicePredicate predicate, final Extractor<K> keyExtractor) throws HectorException {
    Operation<Map<K, List<SuperColumn>>> getCount = new Operation<Map<K, List<SuperColumn>>>(
        OperationType.READ) {
    
      public Map<K, List<SuperColumn>> execute(Cassandra.Client cassandra)
          throws HectorException {
        try {
          Map<byte[], List<ColumnOrSuperColumn>> cfmap = cassandra.multiget_slice(
              keyExtractor.toBytesList(keys), columnParent, predicate, consistency);
          // if user not given super column name, the multiget_slice will return
          // List
          // filled with
          // super column, if user given a column name, the return List will
          // filled
          // with column,
          // this is a bad interface design.
          if (columnParent.getSuper_column() == null) {
            Map<K, List<SuperColumn>> result = new HashMap<K, List<SuperColumn>>();
            for (Map.Entry<byte[], List<ColumnOrSuperColumn>> entry : cfmap.entrySet()) {
              result.put(keyExtractor.fromBytes(entry.getKey()), getSuperColumnList(entry.getValue()));
            }
            return result;
          } else {
            Map<K, List<SuperColumn>> result = new HashMap<K, List<SuperColumn>>();
            for (Map.Entry<byte[], List<ColumnOrSuperColumn>> entry : cfmap.entrySet()) {
              SuperColumn spc = new SuperColumn(columnParent.getSuper_column(),
                  getColumnList(entry.getValue()));
              ArrayList<SuperColumn> spclist = new ArrayList<SuperColumn>(1);
              spclist.add(spc);
              result.put(keyExtractor.fromBytes(entry.getKey()), spclist);
            }
            return result;
          }
        } catch (Exception e) {
          throw xtrans.translate(e);
        }
      }
    };
    operateWithFailover(getCount);
    return getCount.getResult();

  }


  public <K> void remove(K key, ColumnPath columnPath, Extractor<K> keyExtractor) {
  this.remove(key, columnPath, createClock(), keyExtractor);
}



public <K> void remove(final K key, final ColumnPath columnPath, final Clock clock, final Extractor<K> keyExtractor)
      throws HectorException {
    Operation<Void> op = new Operation<Void>(OperationType.WRITE) {
    
      public Void execute(Cassandra.Client cassandra) throws HectorException {
        try {
          cassandra.remove(keyExtractor.toBytes(key), columnPath, clock, consistency);
          return null;
        } catch (Exception e) {
          throw xtrans.translate(e);
        }
      }
    };
    operateWithFailover(op);
  }

public void remove(String key, ColumnPath columnPath) throws HectorException {
  remove(key, columnPath, StringExtractor.get());
}

/**
* Same as two argument version, but the caller must specify their own timestamp
*/
public void remove(String key, ColumnPath columnPath, long timestamp) throws HectorException {
  remove(key, columnPath, new Clock(timestamp), StringExtractor.get());
}


  public String getName() {
    return keyspaceName;
  }


  public Map<String, Map<String, String>> describeKeyspace() throws HectorException {
    return keyspaceDesc;
  }


  public CassandraClient getClient() {
    return client;
  }


  public <K> Column getColumn(final K key, final ColumnPath columnPath, final Extractor<K> keyExtractor) throws HectorException {
    valideColumnPath(columnPath);

    Operation<Column> op = new Operation<Column>(OperationType.READ) {
    
      public Column execute(Cassandra.Client cassandra) throws HectorException {
        ColumnOrSuperColumn cosc;
        try {
          cosc = cassandra.get(keyExtractor.toBytes(key), columnPath, consistency);
        } catch (NotFoundException e) {
          setException(xtrans.translate(e));
          return null;
        } catch (Exception e) {
          throw xtrans.translate(e);
        }
        return cosc == null ? null : cosc.getColumn();
      }

    };
    operateWithFailover(op);
    if (op.hasException()) {
      throw op.getException();
    }
    return op.getResult();

  }

  public Column getColumn(String key, ColumnPath columnPath) throws HectorException {
    return getColumn(key, columnPath, StringExtractor.get());
  }

  public ConsistencyLevel getConsistencyLevel() {
    return consistency;
  }


  public Clock createClock() {
    return client.getClockResolution().createClock();
  }

  /**
   * Make sure that if the given column path was a Column. Throws an
   * InvalidRequestException if not.
   *
   * @param columnPath
   * @throws InvalidRequestException
   *           if either the column family does not exist or that it's type does
   *           not match (super)..
   */
  private void valideColumnPath(ColumnPath columnPath) throws InvalidRequestException {
    String cf = columnPath.getColumn_family();
    Map<String, String> cfdefine;
    String errorMsg;
    if ((cfdefine = keyspaceDesc.get(cf)) != null) {
      if (cfdefine.get(CF_TYPE).equals(CF_TYPE_STANDARD) && columnPath.getColumn() != null) {
        // if the column family is a standard column
        return;
      } else if (cfdefine.get(CF_TYPE).equals(CF_TYPE_SUPER)
          && columnPath.getSuper_column() != null) {
        // if the column family is a super column and also give the super_column
        // name
        return;
      } else {
        errorMsg = new String("Invalid Request for column family " + cf
            + " Make sure you have the right type");
      }
    } else {
      errorMsg = new String("The specified column family does not exist: " + cf);
    }
    throw new InvalidRequestException(errorMsg);
  }

  /**
   * Make sure that the given column path is a SuperColumn in the DB, Throws an
   * exception if it's not.
   *
   * @throws InvalidRequestException
   */
  private void valideSuperColumnPath(ColumnPath columnPath) throws InvalidRequestException {
    String cf = columnPath.getColumn_family();
    Map<String, String> cfdefine;
    if ((cfdefine = keyspaceDesc.get(cf)) != null && cfdefine.get(CF_TYPE).equals(CF_TYPE_SUPER)
        && columnPath.getSuper_column() != null) {
      return;
    }
    throw new InvalidRequestException(
        "Invalid super column name or super column family does not exist: " + cf);
  }

  private static List<ColumnOrSuperColumn> getSoscList(List<Column> columns) {
    ArrayList<ColumnOrSuperColumn> list = new ArrayList<ColumnOrSuperColumn>(columns.size());
    for (Column col : columns) {
      ColumnOrSuperColumn columnOrSuperColumn = new ColumnOrSuperColumn();
      columnOrSuperColumn.setColumn(col);
      list.add(columnOrSuperColumn);
    }
    return list;
  }

  private static List<ColumnOrSuperColumn> getSoscSuperList(List<SuperColumn> columns) {
    ArrayList<ColumnOrSuperColumn> list = new ArrayList<ColumnOrSuperColumn>(columns.size());
    for (SuperColumn col : columns) {
      ColumnOrSuperColumn columnOrSuperColumn = new ColumnOrSuperColumn();
      columnOrSuperColumn.setSuper_column(col);
      list.add(columnOrSuperColumn);
    }
    return list;
  }

  private static List<Column> getColumnList(List<ColumnOrSuperColumn> columns) {
    ArrayList<Column> list = new ArrayList<Column>(columns.size());
    for (ColumnOrSuperColumn col : columns) {
      list.add(col.getColumn());
    }
    return list;
  }

  private static List<SuperColumn> getSuperColumnList(List<ColumnOrSuperColumn> columns) {
    ArrayList<SuperColumn> list = new ArrayList<SuperColumn>(columns.size());
    for (ColumnOrSuperColumn col : columns) {
      list.add(col.getSuper_column());
    }
    return list;
  }


  public FailoverPolicy getFailoverPolicy() {
    return failoverPolicy;
  }



  public String toString() {
    StringBuilder b = new StringBuilder();
    b.append("KeyspaceImpl<");
    b.append(getClient());
    b.append(">");
    return super.toString();
  }
}
