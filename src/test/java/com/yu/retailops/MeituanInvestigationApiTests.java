package com.yu.retailops;

import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.domain.Investigation.*;
import com.yu.retailops.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.convention.TestBean;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static com.yu.retailops.tool.ReadOnlyGateway.*;
import static org.junit.jupiter.api.Assertions.*;

/** Scripted decisions validate runtime wiring, not Bailian reasoning quality. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="omniops.data-dir=${java.io.tmpdir}/omniops-meituan-http-${random.uuid}")
class MeituanInvestigationApiTests {
    @TestBean(name="agentPlanner",methodName="planner") Planner agentPlanner;
    static Planner planner() {
        return new InvestigationTests.ScriptedPlanner(c -> {
            String serialized=JsonMapper.builder().build().writeValueAsString(c);
            assertFalse(serialized.contains("fixtureId"));assertFalse(serialized.contains("requiredObservations"));
            assertFalse(serialized.contains("meituan-timeout-recovery"));
            var platform=ReportPolicy.latest(c.evidence(),PLATFORM);var logs=ReportPolicy.latest(c.evidence(),LOGS);
            var initial=new Hypothesis("H1","当前 POS 尚无接收流水",HypothesisStatus.PROPOSED,
                    List.of(),List.of(),List.of("最新 POS"),TemporalScope.CURRENT,"当前调查时点",Proposition.POS_ABSENT,"最新 POS 接口流水存在");
            List<Hypothesis> updates=c.hypotheses().isEmpty()?List.of(initial):List.of();
            if(platform!=null && platform.result().status()==ResultStatus.TIMEOUT && !c.userGoal().contains("UNTRUSTED_USER_ANSWER"))
                return new Proposal(Action.ASK_USER,"需要补充发生时间",updates,null,List.of(),List.of("大约何时发现异常？"));
            String next;
            if(platform==null && logs==null) next=c.userGoal().contains("先查日志")?LOGS:PLATFORM;
            else if(platform==null || platform.result().status()==ResultStatus.TIMEOUT) next=PLATFORM;
            else if(logs==null) next=LOGS;
            else if(!c.evidence().get(c.evidence().size()-1).tool().equals(POS)) next=POS;
            else {
                var pos=ReportPolicy.latest(c.evidence(),POS);
                boolean found="FOUND".equals(pos.result().outcome());
                var revised=new Hypothesis(initial.id(),initial.statement(),found?HypothesisStatus.CONTRADICTED:HypothesisStatus.UNCONFIRMED,
                        List.of(),found?List.of(pos.id()):List.of(),found?List.of():List.of("历史证据"),
                        initial.temporalScope(),initial.timeWindow(),initial.proposition(),initial.falsificationCriterion());
                return new Proposal(Action.NEEDS_HUMAN,"保留当前状态，历史根因仍需核实",List.of(revised),null,
                        List.of(new Claim("当前查询不能单独确认历史原因",List.of(logs.id()),Qualification.UNKNOWN)),List.of("历史因果尚未确认"));
            }
            return new Proposal(Action.QUERY,"依据新观察选择下一项核查",updates,
                    new Query(next,new Arguments(c.storeId(),c.orderId()),"H1","核实接收状态及调查线索"),List.of(),List.of());
        });
    }
    @Value("${local.server.port}") int port;
    final HttpClient http=HttpClient.newHttpClient();final JsonMapper json=JsonMapper.builder().build();
    HttpResponse<String> post(String path,Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    String create(String fixture,String goal,Intent intent) throws Exception {
        var response=post("/tasks",Map.of("userGoal",goal,"storeId","A001","orderId","O100","fixtureId",fixture,"strategy","investigation","intent",intent));
        assertEquals(202,response.statusCode());return json.readTree(response.body()).path("id").asText();
    }
    Task await(String id,Status status) throws Exception {
        for(int i=0;i<300;i++){var t=json.readValue(get("/tasks/"+id).body(),Task.class);if(t.status==status)return t;Thread.sleep(10);}
        throw new AssertionError("Expected "+status+": "+get("/tasks/"+id).body());
    }
    @ParameterizedTest @CsvSource({"complete,RESTORE_RECOMMENDED,COMPLETED", "incomplete,UNRESOLVED,NEEDS_HUMAN",
            "wrong-order,UNRESOLVED,NEEDS_HUMAN", "wrong-store,UNRESOLVED,NEEDS_HUMAN", "outside-window,OUTSIDE_SEARCH_WINDOW,COMPLETED",
            "timeout,UNRESOLVED,NEEDS_HUMAN", "partial,UNRESOLVED,NEEDS_HUMAN", "multiple,UNRESOLVED,NEEDS_HUMAN"})
    void statusApiChecksContractsWithoutModelCalls(String fixture,Outcome outcome,Status status) throws Exception {
        String id=create("meituan-"+fixture,"核对状态",Intent.CHECK_STATUS);Task t=await(id,status);
        assertEquals(outcome,t.report.outcome());assertEquals(0,t.decisions);
        assertEquals(List.of(POS,LOCAL,PLATFORM),t.evidence.stream().map(Evidence::tool).toList());
        var report=get("/tasks/"+id+"/report");assertEquals(200,report.statusCode());
        assertTrue(report.body().contains("OBSERVE_ONLY"));
    }
    @Test void modelSelectedOrderIsRespectedAndNewPosEvidenceRevisesHypothesis() throws Exception {
        for(boolean logFirst:List.of(true,false)) {
            String id=create("meituan-late-receipt",logFirst?"先查日志再调查":"调查接收情况",Intent.DIAGNOSE_CAUSE);
            Task t=await(id,Status.NEEDS_HUMAN);
            assertEquals(List.of(POS,LOCAL,logFirst?LOGS:PLATFORM,logFirst?PLATFORM:LOGS,POS),t.evidence.stream().map(Evidence::tool).toList());
            assertEquals(Outcome.ALREADY_SYNCED,t.report.outcome());assertEquals(4,t.decisions);
            assertEquals(HypothesisStatus.CONTRADICTED,t.investigation.hypotheses.get(0).status());
            assertEquals(2,t.investigation.hypothesisHistory.size());
            assertTrue(t.investigation.turns.stream().allMatch(Turn::accepted));
            assertEquals(200,get("/tasks/"+id+"/investigation").statusCode());
            assertTrue(get("/tasks/"+id+"/events").body().contains("EVIDENCE_RECORDED"));
        }
    }
    @Test void timeoutCanPauseForUserAndResumeWithoutResettingResponsesOrBudget() throws Exception {
        String id=create("meituan-timeout-recovery","调查缺单",Intent.DIAGNOSE_CAUSE);
        Task waiting=await(id,Status.WAITING_USER);assertEquals(2,waiting.decisions);
        assertEquals(ResultStatus.TIMEOUT,ReportPolicy.latest(waiting.evidence,PLATFORM).result().status());
        HttpResponse<String> response=null;
        for(int i=0;i<100;i++) {
            response=post("/tasks/"+id+"/resume",Map.of("expectedVersion",waiting.version,"answer","十分钟前发现异常"));
            if(response.statusCode()!=409)break;Thread.sleep(10);
        }
        assertNotNull(response);assertEquals(202,response.statusCode());
        Task done=await(id,Status.NEEDS_HUMAN);
        assertEquals(6,done.decisions);assertEquals(6,done.toolCalls);
        assertEquals(180,done.promptTokens);assertEquals(1,done.agentState.answers.size());
        assertEquals(Outcome.RESTORE_RECOMMENDED,done.report.outcome());
        assertEquals(List.of(ResultStatus.TIMEOUT,ResultStatus.SUCCESS),done.evidence.stream().filter(e->e.tool().equals(PLATFORM)).map(e->e.result().status()).toList());
        assertEquals(409,post("/tasks/"+id+"/resume",Map.of("expectedVersion",waiting.version,"answer","重复")).statusCode());
    }
}
