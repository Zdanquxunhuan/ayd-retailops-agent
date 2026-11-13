package com.yu.retailops.tool;

import com.yu.retailops.domain.Diagnosis.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Fixed SQL and server-owned single-store routing; no payload or arbitrary SQL reaches the model. */
public final class JdbcOrderAdapter {
    private final DataSource source;
    private final String store, branch;
    private final boolean pos;
    private static final String LOCAL_SQL = "SELECT order_id, app_poi_code, created_date, updated_date FROM mt_order_data WHERE order_id = ?";
    private static final String POS_SQL = "SELECT CASE WHEN EXISTS (SELECT 1 FROM dbo.t_rm_saleflow_frommail WHERE mail_orderid = ? AND branch_no = ?) THEN 1 ELSE 0 END AS sale_present, "
        + "CASE WHEN EXISTS (SELECT 1 FROM dbo.t_rm_payflow_frommail WHERE mail_orderid = ? AND branch_no = ?) THEN 1 ELSE 0 END AS pay_present";
    public JdbcOrderAdapter(DataSource source, String store, String branch, boolean pos) {
        this.source=Objects.requireNonNull(source);this.store=required(store,64);this.pos=pos;
        this.branch=pos?required(branch,8):"";
    }
    private static String required(String value,int max) {
        if(value==null || !value.matches("[A-Za-z0-9_-]{1,"+max+"}"))throw new IllegalArgumentException("Invalid server-owned store mapping");
        return value;
    }
    public ToolRegistry.Definition definition() {
        return new ToolRegistry.Definition(pos?ReadOnlyGateway.POS:ReadOnlyGateway.LOCAL,"jdbc-read-v1",
            pos?"查询配置 POS 库和门店的销售/收款接口流水，任一存在表示已接收，不代表 POS 业务已消费完成。"
                :"查询本地 MySQL 订单元数据；无推送状态字段，异步落库期间暂时未找到不能推断回调没到达。",
            6000,6000,false,this::query);
    }
    private ToolResult query(Input input,List<Evidence> history) {
        if(!store.equals(input.storeId()))return failure(ResultStatus.DENIED,"STORE_DENIED");
        // Original schema uses Long order_id; accept canonical positive integer IDs only.
        if(input.orderId()==null || !input.orderId().matches("[1-9][0-9]{0,18}"))return failure(ResultStatus.ERROR,"INVALID_ORDER_ID");
        long order;
        try {order=Long.parseLong(input.orderId());} catch(NumberFormatException e){return failure(ResultStatus.ERROR,"INVALID_ORDER_ID");}
        try(Connection connection=source.getConnection();PreparedStatement statement=connection.prepareStatement(pos?POS_SQL:LOCAL_SQL)) {
            statement.setQueryTimeout(2);statement.setMaxRows(2);
            if(pos) {
                statement.setString(1,input.orderId());statement.setString(2,branch);
                statement.setString(3,input.orderId());statement.setString(4,branch);
            } else statement.setLong(1,order);
            try(ResultSet rows=statement.executeQuery()) {
                if(pos) {
                    if(!rows.next())return failure(ResultStatus.UNKNOWN,"INVALID_DB_RESULT");
                    int sale=rows.getInt("sale_present");boolean nullSale=rows.wasNull();
                    int pay=rows.getInt("pay_present");boolean nullPay=rows.wasNull();
                    if(nullSale || nullPay || (sale!=0&&sale!=1) || (pay!=0&&pay!=1) || rows.next())return failure(ResultStatus.UNKNOWN,"INVALID_DB_RESULT");
                    boolean found=sale==1 || pay==1;
                    return new ToolResult(found?ResultStatus.SUCCESS:ResultStatus.NOT_FOUND,found?"FOUND":"ABSENT",true,
                        Map.of("source","POS_JDBC","storeId",store,"orderId",input.orderId(),"branchNo",branch,
                            "salePresent",sale==1,"payPresent",pay==1,"observationScope","POS_INTERFACE_TABLES",
                            "businessCompletion","UNKNOWN","partialReceipt",sale!=pay),null);
                }
                if(!rows.next())return new ToolResult(ResultStatus.NOT_FOUND,"ABSENT",true,
                    Map.of("source","LOCAL_JDBC","storeId",store,"orderId",input.orderId(),
                        "limitation","Current lookup only; asynchronous persistence may be pending."),null);
                String actualStore=rows.getString("app_poi_code");
                if(!store.equals(actualStore))return failure(ResultStatus.DENIED,"ORDER_STORE_MISMATCH");
                var data=new LinkedHashMap<String,Object>();
                data.put("source","LOCAL_JDBC");data.put("storeId",store);data.put("orderId",input.orderId());
                data.put("createdDate",Objects.toString(rows.getTimestamp("created_date"),"UNKNOWN"));
                data.put("updatedDate",Objects.toString(rows.getTimestamp("updated_date"),"UNKNOWN"));
                data.put("pushStatus","NOT_RECORDED");data.put("payloadValidated",false);
                data.put("limitation","Uploaded saveData maps ctime/utime to these fields in +08:00; not persistence or push time. Payload was not read.");
                if(rows.next())return failure(ResultStatus.UNKNOWN,"DUPLICATE_LOCAL_ORDER");
                return new ToolResult(ResultStatus.SUCCESS,"FOUND",true,Map.copyOf(data),null);
            }
        } catch(SQLTimeoutException e) {return failure(ResultStatus.TIMEOUT,"DB_TIMEOUT");}
        catch(SQLException e) {return failure(ResultStatus.ERROR,"DB_QUERY_FAILED");}
    }
    private static ToolResult failure(ResultStatus status,String code) {
        return new ToolResult(status,"INDETERMINATE",false,Map.of(),code);
    }
}
