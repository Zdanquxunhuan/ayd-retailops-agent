package com.yu.retailops;

import com.yu.retailops.tool.*;
import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.runtime.ReportPolicy;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

class JdbcOrderAdapterTests {
    JdbcDataSource local,pos;
    final Input input=new Input("why","C100001","3200000000000000001","restore","investigation");
    @BeforeEach void setup() throws Exception {
        local=source("MySQL");pos=source("MSSQLServer");
        sql(local,"CREATE TABLE mt_order_data(order_id BIGINT,app_poi_code VARCHAR(64),created_date TIMESTAMP,updated_date TIMESTAMP,data CLOB,member_id VARCHAR(64))");
        sql(pos,"CREATE SCHEMA dbo", "CREATE TABLE dbo.t_rm_saleflow_frommail(mail_orderid VARCHAR(30),branch_no VARCHAR(8))",
            "CREATE TABLE dbo.t_rm_payflow_frommail(mail_orderid VARCHAR(30),branch_no VARCHAR(8))");
    }
    JdbcDataSource source(String mode) {var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE="+mode+";DB_CLOSE_DELAY=-1");ds.setUser("sa");return ds;}
    void sql(DataSource ds,String... statements) throws Exception {try(var c=ds.getConnection();var s=c.createStatement()){for(var q:statements)s.execute(q);}}
    ToolResult query(boolean isPos) throws Exception {return new JdbcOrderAdapter(isPos?pos:local,"C100001","SX000001",isPos).definition().handler().execute(input,List.of());}
    void localOrder(String store) throws Exception {sql(local,"INSERT INTO mt_order_data VALUES(3200000000000000001,'"+store+"',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'customer secret payload','private-member')");}
    @Test void localMetadataExcludesPayloadAndPushState() throws Exception {
        localOrder("C100001");var r=query(false);assertEquals("FOUND",r.outcome());assertTrue(r.complete());
        assertEquals("NOT_RECORDED",r.data().get("pushStatus"));assertEquals(false,r.data().get("payloadValidated"));
        assertFalse(r.toString().contains("secret"));assertFalse(r.toString().contains("private-member"));
    }
    @Test void localAbsentIsOnlyCurrentObservation() throws Exception {var r=query(false);assertEquals(ResultStatus.NOT_FOUND,r.status());assertTrue(r.toString().contains("asynchronous"));}
    @Test void mismatchedStoreAndDuplicatesAreNotFoundEvidence() throws Exception {
        localOrder("C200002");assertEquals(ResultStatus.DENIED,query(false).status());
        sql(local,"DELETE FROM mt_order_data");localOrder("C100001");localOrder("C100001");
        assertEquals("DUPLICATE_LOCAL_ORDER",query(false).errorCode());assertFalse(query(false).complete());
    }
    @Test void missingTableIsErrorNotAbsence() throws Exception {sql(pos,"DROP TABLE dbo.t_rm_payflow_frommail");var r=query(true);assertEquals(ResultStatus.ERROR,r.status());assertFalse(r.complete());assertFalse(r.toString().contains("SELECT"));}
    @Test void posEitherTableMeansReceiptNotBusinessCompletion() throws Exception {
        assertEquals("ABSENT",query(true).outcome());
        sql(pos,"INSERT INTO dbo.t_rm_saleflow_frommail VALUES('3200000000000000001','SX000001')");
        var r=query(true);assertEquals("FOUND",r.outcome());assertEquals(true,r.data().get("partialReceipt"));assertEquals("UNKNOWN",r.data().get("businessCompletion"));
        sql(pos,"INSERT INTO dbo.t_rm_payflow_frommail VALUES('3200000000000000001','SX000001')");assertEquals(false,query(true).data().get("partialReceipt"));
        sql(pos,"DELETE FROM dbo.t_rm_saleflow_frommail");assertEquals("FOUND",query(true).outcome());
    }
    @Test void posOtherBranchDoesNotMatch() throws Exception {sql(pos,"INSERT INTO dbo.t_rm_payflow_frommail VALUES('3200000000000000001','SX000002')");assertEquals("ABSENT",query(true).outcome());}
    @Test void inputValidationPrecedesConnection() throws Exception {
        var broken=source("MySQL");broken.setURL("jdbc:h2:unsupported");var d=new JdbcOrderAdapter(broken,"C100001","",false).definition();
        assertEquals("STORE_DENIED",d.handler().execute(new Input("q","OTHER","123","restore","investigation"),List.of()).errorCode());
        for(String id:List.of("1 OR 1=1","../x","9223372036854775808","001"))
            assertEquals("INVALID_ORDER_ID",d.handler().execute(new Input("q","C100001",id,"restore","investigation"),List.of()).errorCode());
    }
    @Test void readOnlyAccountCanRunAdapterWithoutReadingPayloadColumns() throws Exception {
        localOrder("C100001");
        sql(local,"CREATE USER reader PASSWORD 'test-only'","GRANT SELECT ON mt_order_data TO reader");
        var reader=new JdbcDataSource();reader.setURL(local.getURL().split(";")[0]);reader.setUser("reader");reader.setPassword("test-only");
        assertEquals("FOUND",new JdbcOrderAdapter(reader,"C100001","",false).definition().handler().execute(input,List.of()).outcome());
        assertThrows(SQLException.class,()->sql(reader,"DELETE FROM mt_order_data"));
    }
    @Test void databaseTimeoutIsNotAbsenceAndMessageIsNotLeaked() throws Exception {
        DataSource timeout=(DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataSource.class},(p,m,a)->{throw new SQLTimeoutException("password=secret");});
        var r=new JdbcOrderAdapter(timeout,"C100001","",false).definition().handler().execute(input,List.of());
        assertEquals(ResultStatus.TIMEOUT,r.status());assertEquals("DB_TIMEOUT",r.errorCode());assertFalse(r.toString().contains("secret"));
    }
    @Test void gatewayMarksJdbcObservedAndValidatesTarget() throws Exception {
        localOrder("C100001");
        try(var gateway=new ReadOnlyGateway(new ToolRegistry(List.of(new JdbcOrderAdapter(local,"C100001","",false).definition())),"C100001",Clock.systemUTC())) {
            assertFalse(gateway.invoke(input,Decision.call(ReadOnlyGateway.LOCAL,input,"check"),List.of()).simulated());
            assertThrows(SecurityException.class,()->gateway.invoke(input,new Decision(DecisionType.CALL_TOOL,ReadOnlyGateway.LOCAL,new Arguments("C100001","2"),null,List.of(),"wrong target"),List.of()));
        }
    }
    @Test void realLocalAndPosSupportProposalButMockPlatformCannotSupportRestoration() throws Exception {
        var clock=Clock.systemUTC();var policy=new ReportPolicy(clock);
        var defs=List.of(new JdbcOrderAdapter(local,"C100001","",false).definition(),new JdbcOrderAdapter(pos,"C100001","SX000001",true).definition());
        try(var gateway=new ReadOnlyGateway(new ToolRegistry(defs),"C100001",clock)) {
            localOrder("C100001");var observations=new ArrayList<Evidence>();
            observations.add(gateway.invoke(input,Decision.call(ReadOnlyGateway.LOCAL,input,"local"),List.of()));
            observations.add(gateway.invoke(input,Decision.call(ReadOnlyGateway.POS,input,"pos"),List.of()));
            assertEquals(Outcome.REPUSH_RECOMMENDED,policy.supported(observations));
            sql(local,"DELETE FROM mt_order_data");observations.add(gateway.invoke(input,Decision.call(ReadOnlyGateway.LOCAL,input,"recheck"),List.of()));
            observations.add(new Evidence("mock",ReadOnlyGateway.PLATFORM,new Arguments(input.storeId(),input.orderId()),new ToolResult(ResultStatus.SUCCESS,"FOUND",true,Map.of(),null),clock.instant(),clock.instant().plusSeconds(60),true));
            assertEquals(Outcome.UNRESOLVED,policy.supported(observations));
            var report=policy.validate(Decision.finish(Outcome.UNRESOLVED,observations,"missing real platform"),observations);
            assertEquals("MIXED_READ_ONLY",report.dataSource());assertTrue(report.summary().contains("模拟"));
        }
    }
    @Test void investigationCollectsJdbcThenModelChoosesLogEvidence() throws Exception {
        localOrder("C100001");
        var clock=Clock.systemUTC();var policy=new ReportPolicy(clock);
        var logs=new ToolRegistry.Definition(ReadOnlyGateway.LOGS,"test-v1","synthetic log observation",1000,2000,false,
            (i,h)->new ToolResult(ResultStatus.SUCCESS,"LOG_SNAPSHOT",false,Map.of("lines",List.of()),null));
        var defs=List.of(new JdbcOrderAdapter(local,"C100001","",false).definition(),new JdbcOrderAdapter(pos,"C100001","SX000001",true).definition(),logs);
        class Script implements com.yu.retailops.runtime.Planner,com.yu.retailops.runtime.InvestigationPlanner {
            public String provider(){return "TEST_DOUBLE_NOT_LLM";}
            public String modelVersion(){return "contract-test";}
            public ModelReply next(com.yu.retailops.runtime.Planner.Context c){throw new AssertionError();}
            public com.yu.retailops.domain.Investigation.Reply investigate(com.yu.retailops.domain.Investigation.Context c) {
                assertEquals(com.yu.retailops.domain.Investigation.Environment.MIXED_READ_ONLY,c.executionEnvironment());
                assertTrue(c.evidence().stream().filter(e->Set.of(ReadOnlyGateway.LOCAL,ReadOnlyGateway.POS).contains(e.tool())).allMatch(e->!e.simulated()));
                if(c.hypotheses().isEmpty()) {
                    var h=new com.yu.retailops.domain.Investigation.Hypothesis("H1","历史推送可能失败",com.yu.retailops.domain.Investigation.HypothesisStatus.PROPOSED,List.of(),List.of(),List.of("历史日志"));
                    return new com.yu.retailops.domain.Investigation.Reply(new com.yu.retailops.domain.Investigation.Proposal(
                        com.yu.retailops.domain.Investigation.Action.QUERY,"查历史原因",List.of(h),
                        new com.yu.retailops.domain.Investigation.Query(ReadOnlyGateway.LOGS,new Arguments(input.storeId(),input.orderId()),"H1","是否有失败记录"),List.of(),List.of(),List.of()),0,0);
                }
                assertTrue(c.evidence().stream().anyMatch(e->e.tool().equals(ReadOnlyGateway.LOGS)));
                var reviews=c.hypotheses().stream().map(h->new com.yu.retailops.domain.Investigation.Review(h.id(),c.newEvidenceIds(),com.yu.retailops.domain.Investigation.ReviewDisposition.MAINTAIN,"日志无记录，仍待核实")).toList();
                return new com.yu.retailops.domain.Investigation.Reply(new com.yu.retailops.domain.Investigation.Proposal(
                    com.yu.retailops.domain.Investigation.Action.NEEDS_HUMAN,"原因未知",c.hypotheses(),null,List.of(),List.of("缺少关联日志"),reviews),0,0);
            }
        }
        try(var gateway=new ReadOnlyGateway(new ToolRegistry(defs),"C100001",clock);
            var runner=new com.yu.retailops.runtime.DiagnosisRunner(gateway,policy,clock,8,12,10)) {
            Task task=new Task();task.id=UUID.randomUUID().toString();task.input=input;task.createdAt=clock.instant();
            runner.run(task,new Script(),t->{},()->false);
            assertEquals(Status.NEEDS_HUMAN,task.status);assertEquals(3,task.toolCalls);
            assertEquals(Outcome.REPUSH_RECOMMENDED,task.report.outcome());
            assertEquals("UNCONFIRMED",task.report.rootCauseStatus());
            assertEquals("INVESTIGATION_NEEDS_HUMAN",task.investigation.stopCode);
        }
    }
}
