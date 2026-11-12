package com.yu.retailops;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="omniops.data-dir=${java.io.tmpdir}/omniops-api-${random.uuid}")
class TaskApiTests {
    @Value("${local.server.port}") int port;
    final HttpClient http=HttpClient.newHttpClient();
    final JsonMapper json=JsonMapper.builder().build();
    HttpResponse<String> post(String path,String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void realHttpTaskToReportAndEvents() throws Exception {
        var created=post("/api/tasks","""
                {"userGoal":"调查订单","storeId":"A001","orderId":"O100","fixtureId":"restore"}
                """);
        assertEquals(202,created.statusCode());
        String id=json.readTree(created.body()).path("id").asText();
        String report="";
        for(int i=0;i<100;i++) {
            var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/tasks/"+id+"/report")).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==200) {report=response.body();break;}
            Thread.sleep(20);
        }
        assertFalse(report.isEmpty());
        assertEquals("RESTORE_RECOMMENDED",json.readTree(report).path("outcome").asText());
        assertEquals("STUB",json.readTree(report).path("dataSource").asText());
        var events=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/tasks/"+id+"/events?afterSequence=0")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertTrue(json.readTree(events.body()).size()>3);
    }
    @Test void crossStoreAndUnknownFixtureAreRejected() throws Exception {
        assertEquals(403,post("/api/tasks","""
                {"userGoal":"调查","storeId":"B002","orderId":"O100"}
                """).statusCode());
        assertEquals(400,post("/api/tasks","""
                {"userGoal":"调查","storeId":"A001","orderId":"O100","fixtureId":"fake"}
                """).statusCode());
    }
    @Test void investigationApiReturnsZeroModelStatusTrace() throws Exception {
        var created = post("/api/tasks", """
                {"userGoal":"查当前状态","storeId":"A001","orderId":"O100","fixtureId":"synced","intent":"CHECK_STATUS"}
                """);
        assertEquals(202, created.statusCode());
        String id = json.readTree(created.body()).path("id").asText();
        for (int i = 0; i < 100; i++) {
            var result = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/tasks/"+id+"/investigation")).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (result.statusCode() == 200 && "STATUS_CHECK_READY".equals(json.readTree(result.body()).path("stopCode").asText())) {
                assertEquals(0, json.readTree(result.body()).path("turns").size());
                assertEquals("SIMULATION", json.readTree(result.body()).path("executionEnvironment").asText());
                return;
            }
            Thread.sleep(20);
        }
        fail("Investigation trace not completed");
    }
}
