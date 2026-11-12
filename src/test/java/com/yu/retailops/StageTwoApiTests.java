package com.yu.retailops;

import com.yu.retailops.runtime.Planner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.convention.TestBean;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="omniops.data-dir=${java.io.tmpdir}/omniops-stage2-http-${random.uuid}")
class StageTwoApiTests {
    @TestBean(name="agentPlanner", methodName="planner") Planner agentPlanner;
    static Planner planner() { return new StageTwoTests.AskingPlanner(); }
    @Value("${local.server.port}") int port;
    final HttpClient http=HttpClient.newHttpClient();
    final JsonMapper json=JsonMapper.builder().build();
    HttpResponse<String> post(String path,Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    com.yu.retailops.domain.Diagnosis.Task get(String id) throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/tasks/"+id)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());
        return json.readValue(response.body(),com.yu.retailops.domain.Diagnosis.Task.class);
    }
    com.yu.retailops.domain.Diagnosis.Task waitFor(String id,String status) throws Exception {
        for(int i=0;i<200;i++) {var t=get(id);if(t.status.name().equals(status))return t;Thread.sleep(10);}
        throw new AssertionError("Expected "+status);
    }
    String create() throws Exception {
        var response=post("/api/tasks",Map.of("userGoal","调查原因","storeId","A001","orderId","O100","fixtureId","synced","strategy","investigation"));
        assertEquals(202,response.statusCode());
        return json.readTree(response.body()).path("id").asText();
    }
    @Test void answerViaHttpContinuesSameTaskAndRejectsDuplicate() throws Exception {
        String id=create();var waiting=waitFor(id,"WAITING_USER");
        assertEquals(409,post("/api/tasks/"+id+"/resume",Map.of("expectedVersion",waiting.version+1,"answer","昨天")).statusCode());
        HttpResponse<String> response=null;
        for(int i=0;i<100;i++) {
            response=post("/api/tasks/"+id+"/resume",Map.of("expectedVersion",waiting.version,"answer","十分钟前"));
            if(response.statusCode()!=409)break;
            Thread.sleep(10);
        }
        assertNotNull(response);assertEquals(202,response.statusCode());
        var done=waitFor(id,"NEEDS_HUMAN");
        assertEquals(2,done.decisions);assertEquals(2,done.investigation.turns.size());
        assertEquals("十分钟前",done.agentState.answers.get(0).answer());
        assertEquals(409,post("/api/tasks/"+id+"/resume",Map.of("expectedVersion",waiting.version,"answer","重复")).statusCode());
    }
    @Test void cancelledTaskCannotResumeThroughHttp() throws Exception {
        String id=create();waitFor(id,"WAITING_USER");
        assertEquals(202,post("/api/tasks/"+id+"/cancel",Map.of()).statusCode());
        var cancelled=waitFor(id,"CANCELLED");
        assertEquals(409,post("/api/tasks/"+id+"/resume",Map.of("expectedVersion",cancelled.version,"answer","昨天")).statusCode());
    }
    @Test void malformedAnswerAndMissingTaskAreRejected() throws Exception {
        String id=create();var waiting=waitFor(id,"WAITING_USER");
        HttpResponse<String> response=null;
        for(int i=0;i<100;i++) {
            response=post("/api/tasks/"+id+"/resume",Map.of("expectedVersion",waiting.version,"answer"," "));
            if(response.statusCode()!=409)break;
            Thread.sleep(10);
        }
        assertNotNull(response);assertEquals(400,response.statusCode());
        assertEquals(404,post("/api/tasks/00000000-0000-0000-0000-000000000000/resume",Map.of("expectedVersion",0,"answer","x")).statusCode());
    }
}
