package de.jbellmann.tomcat.cassandra.astyanax;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import com.netflix.astyanax.AstyanaxContext;
import com.netflix.astyanax.Cluster;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.MutationBatch;
import com.netflix.astyanax.connectionpool.NodeDiscoveryType;
import com.netflix.astyanax.connectionpool.OperationResult;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import com.netflix.astyanax.connectionpool.impl.ConnectionPoolConfigurationImpl;
import com.netflix.astyanax.connectionpool.impl.Slf4jConnectionPoolMonitorImpl;
import com.netflix.astyanax.ddl.KeyspaceDefinition;
import com.netflix.astyanax.impl.AstyanaxConfigurationImpl;
import com.netflix.astyanax.model.Column;
import com.netflix.astyanax.model.ColumnFamily;
import com.netflix.astyanax.model.ColumnList;
import com.netflix.astyanax.model.Row;
import com.netflix.astyanax.model.Rows;
//import com.netflix.astyanax.serializers.ObjectSerializer;
import com.netflix.astyanax.serializers.StringSerializer;
import com.netflix.astyanax.thrift.ThriftFamilyFactory;
import com.netflix.astyanax.util.RangeBuilder;

import de.jbellmann.tomcat.cassandra.CassandraTemplate;

/**
 * 
 * 
 * 
 * @author Joerg Bellmann
 * 
 */
public class AstyanaxCassandraTemplate extends CassandraTemplate {
    
    private final Log log = LogFactory.getLog(CassandraTemplate.class);
    
    private AstyanaxContext<Cluster> context;
    private Keyspace keyspace;
    private ColumnFamily<String, Object> columnFamily;
    private String seedHosts = "127.0.0.1:9160";
    
    private de.jbellmann.tomcat.cassandra.astyanax.ClassLoaderAwareObjectSerializer objectSerializer;

    @Override
    public void initialize(ClassLoader classLoader) {
        
        objectSerializer = new de.jbellmann.tomcat.cassandra.astyanax.ClassLoaderAwareObjectSerializer(classLoader);
        
        context = new AstyanaxContext.Builder()
                .forCluster(getClusterName())
                .withAstyanaxConfiguration(new AstyanaxConfigurationImpl().setDiscoveryType(NodeDiscoveryType.NONE))
                .withConnectionPoolConfiguration(
                        new ConnectionPoolConfigurationImpl("MyConnectionPool")
                        .setPort(9160)
                        .setMaxConnsPerHost(1)
                        .setSeeds(seedHosts)
                        .setMaxTimeoutCount(5)
                        .setConnectTimeout(10000))
                .withConnectionPoolMonitor(new Slf4jConnectionPoolMonitorImpl())
                .buildCluster(ThriftFamilyFactory.getInstance());
        
        context.start();
        
        
        try{
            if(context.getClient().describeKeyspace(getKeyspaceName()) == null){
                KeyspaceDefinition ksDef = context.getClient().makeKeyspaceDefinition();
                
                Map<String, String> stratOptions = new HashMap<String, String>();
                stratOptions.put("replication_factor", "1");
                
                ksDef.setName(getKeyspaceName())
                        .setStrategyOptions(stratOptions)
                        .setStrategyClass(getStrategyClassName())
                        .addColumnFamily(
                                context.getClient().makeColumnFamilyDefinition()
                                        .setName(getColumnFamilyName())
                                        .setComparatorType("BytesType")
                                        .setKeyValidationClass("BytesType"));
                
                context.getClient().addKeyspace(ksDef);
            }
        }catch(Exception e){
            log.error(e.getMessage(), e);
        }
        try {
            keyspace = context.getClient().getKeyspace(getKeyspaceName());
        } catch (ConnectionException ce) {
            log.error(ce.getMessage(), ce);
        }
        // in hector we set the classloader to the objectSerializer
        columnFamily = new ColumnFamily<String, Object>(getColumnFamilyName(), StringSerializer.get(), objectSerializer);
    }

    @Override
    public void shutdown() {
        log.info("Shuttingdown context ...");
        context.shutdown();
        log.info("Context is down");
    }
    
    @Override
    public long getCreationTime(final String sessionId) {
        log.info("Get CREATION_TIME for Session : " + sessionId);
        try {
            OperationResult<ColumnList<Object>> result = keyspace.prepareQuery(columnFamily).getKey(sessionId).execute();
            Column<Object> column = result.getResult().getColumnByName(CREATIONTIME_COLUMN_NAME);
            return column != null ? column.getLongValue() : -1;
        } catch (ConnectionException e) {
            log.error("Could not get 'creationTime' because of 'ConnectionException'", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setCreationTime(final String sessionId, final long time) {
        log.info("Set CREATION_TIME for Session : " + sessionId + " to value " + time);
        MutationBatch mutation = keyspace.prepareMutationBatch();
        mutation.withRow(columnFamily, sessionId).putColumn(CREATIONTIME_COLUMN_NAME, time, null);
        try{
            mutation.execute();
        }catch(ConnectionException e){
            log.error("Could not insert 'creationTime' for sessionId " + sessionId, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public long getLastAccessedTime(final String sessionId) {
        log.info("Get LAST_ACCESSED_TIME for Session : " + sessionId);
        try {
            OperationResult<ColumnList<Object>> result = keyspace.prepareQuery(columnFamily).getKey(sessionId).execute();
            Column<Object> column = result.getResult().getColumnByName(LAST_ACCESSTIME_COLUMN_NAME);
            return column != null ? column.getLongValue() : -1;
        } catch (ConnectionException e) {
            log.error("Could not get 'creationTime' because of 'ConnectionException'", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setLastAccessedTime(final String sessionId, final long time) {
        log.info("Set LAST_ACCESSED_TIME for Session : " + sessionId + " to value " + time);
        MutationBatch mutation = keyspace.prepareMutationBatch();
        mutation.withRow(columnFamily, sessionId).putColumn(LAST_ACCESSTIME_COLUMN_NAME, time, null);
        try{
            mutation.execute();
        }catch(ConnectionException e){
            log.error("Could not insert 'lastAccessTime' for sessionId " + sessionId, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Object getAttribute(final String sessionId, final String name) {
        log.info("Get attribute '" + name + "' for Session : " + sessionId);
        try {
            OperationResult<ColumnList<Object>> result = keyspace.prepareQuery(columnFamily).getKey(sessionId).execute();
            Column<Object> column = result.getResult().getColumnByName(name);
            return column != null ? column.getValue(objectSerializer) : null;
        } catch (ConnectionException e) {
            log.error("Could not get 'creationTime' because of 'ConnectionException'", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setAttribute(final String sessionId, final String name, final Object value) {
        if (null == value) {
            log.info("Set attribute '" + name + "' with value null for Session : " + sessionId + "");
        } else {
            log.info("Set attribute '" + name + "' with value " + value.toString() + " for Session : " + sessionId + "");
        }
        MutationBatch mutation = keyspace.prepareMutationBatch();
        mutation.withRow(columnFamily, sessionId).putColumn(name, value, objectSerializer, null);
        try{
            mutation.execute();
        }catch(ConnectionException e){
            log.error("Could not 'setAttribute' for column with name: '"+ name+ "' for sessionId " + sessionId, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void removeAttribute(final String sessionId, final String name) {
        log.info("Remove attribute '" + name + "' for Session : " + sessionId);        
        MutationBatch mutation = keyspace.prepareMutationBatch();
        mutation.withRow(columnFamily, sessionId).deleteColumn(name);
        try{
            mutation.execute();
        }catch(ConnectionException e){
            log.error("Could not delete column '" + name + "' for sessionId " + sessionId, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public String[] keys(final String sessionId) {
        List<String> resultList = new ArrayList<String>();
        ColumnList<Object> columnList = null;
        try {
            columnList = keyspace.prepareQuery(columnFamily).getKey(sessionId).execute().getResult();
        } catch (ConnectionException e) {
            log.error("Could not get all column names for session " + sessionId);
            throw new RuntimeException(e);
        }
        for(Column<Object> column : columnList){
            resultList.add(column.getName().toString());
        }
        return resultList.toArray(new String[resultList.size()]);
    }

    @Override
    public Enumeration<String> getAttributeNames(String sessionId) {
        return Collections.enumeration(Arrays.asList(keys(sessionId)));
    }

    @Override
    public List<String> findSessionKeys() {
        List<String> resultList = new ArrayList<String>();
        Rows<String, Object> result;
        try {
            result = keyspace.prepareQuery(columnFamily)
                      .getAllRows()
                      .withColumnRange(new RangeBuilder().setLimit(0).build())
                      .execute().getResult();
        } catch (ConnectionException e) {
            log.error("Could not get the keys for all rows", e);
            throw new RuntimeException(e);
        }
        for(Row<String,Object> row : result){
            resultList.add(row.getKey());
        }
        return resultList;
    }

    @Override
    public void removeSession(final String sessionId) {
        MutationBatch mutation = keyspace.prepareMutationBatch();
        mutation.withRow(columnFamily, sessionId).delete();
        try{
            mutation.execute();
        }catch(ConnectionException e){
            log.error("Could not delete row for sessionId '" + sessionId, e);
            throw new RuntimeException(e);
        }
    }

    public String getSeedHosts() {
        return seedHosts;
    }

    public void setSeedHosts(String seedHosts) {
        this.seedHosts = seedHosts;
    }

}
