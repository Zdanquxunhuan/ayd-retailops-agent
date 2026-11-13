package com.yu.retailops.application;

import com.yu.retailops.tool.*;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.env.Environment;
import java.util.*;

/** Opt-in pools; closure is managed by Spring. Passwords are never included in tool evidence. */
public final class JdbcToolResources implements AutoCloseable {
    private final List<HikariDataSource> pools=new ArrayList<>();
    private final List<ToolRegistry.Definition> definitions=new ArrayList<>();
    public JdbcToolResources(Environment env,String allowedStore) {
        try {
            for(String name:List.of("local","pos")) {
                String prefix="omniops.jdbc."+name+".";
                String mode=env.getProperty(prefix+"mode","fixture");
                if("fixture".equals(mode))continue;
                if(!"jdbc".equals(mode))throw new IllegalArgumentException("Unknown JDBC adapter mode");
                if(!env.getProperty(prefix+"readonly-account-confirmed",Boolean.class,false))
                    throw new IllegalArgumentException("Confirm SELECT-only DB account in configuration before enabling JDBC");
                boolean pos=name.equals("pos");
                String store=env.getRequiredProperty(prefix+"store-id");
                if(!store.equals(allowedStore))throw new IllegalArgumentException("JDBC store must equal allowed store");
                String url=env.getRequiredProperty(prefix+"url");
                if(!url.startsWith(pos?"jdbc:sqlserver://":"jdbc:mysql://"))throw new IllegalArgumentException("Incorrect database URL scheme");
                HikariDataSource ds=new HikariDataSource();pools.add(ds);
                ds.setPoolName("readonly-"+name);ds.setJdbcUrl(url);
                ds.setUsername(env.getRequiredProperty(prefix+"username"));ds.setPassword(env.getRequiredProperty(prefix+"password"));
                ds.setMaximumPoolSize(2);ds.setMinimumIdle(0);ds.setConnectionTimeout(2000);ds.setValidationTimeout(1000);
                ds.setInitializationFailTimeout(-1);
                if(pos) {
                    ds.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
                    ds.addDataSourceProperty("loginTimeout",2);ds.addDataSourceProperty("socketTimeout",3000);
                    ds.addDataSourceProperty("cancelQueryTimeout",1);
                } else {
                    ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
                    ds.addDataSourceProperty("connectTimeout",2000);ds.addDataSourceProperty("socketTimeout",3000);
                    ds.addDataSourceProperty("allowMultiQueries",false);
                }
                definitions.add(new JdbcOrderAdapter(ds,store,pos?env.getRequiredProperty(prefix+"branch-no"):"",pos).definition());
            }
            if(!definitions.isEmpty()) definitions.add(new ToolRegistry.Definition(ReadOnlyGateway.PUSH_HISTORY,
                "unavailable-v1","尚未接入真实推送历史，只能返回未知；请查询日志。",1000,1000,false,
                (input,history) -> new com.yu.retailops.domain.Diagnosis.ToolResult(
                    com.yu.retailops.domain.Diagnosis.ResultStatus.UNKNOWN,"INDETERMINATE",false,Map.of(),"PUSH_HISTORY_UNAVAILABLE")));
        } catch(RuntimeException e) {close();throw e;}
    }
    public List<ToolRegistry.Definition> definitions(){return List.copyOf(definitions);}
    public void close(){pools.forEach(HikariDataSource::close);}
}
