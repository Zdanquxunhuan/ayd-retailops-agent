package com.yu.retailops;

import com.yu.retailops.application.TaskService;
import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.domain.Investigation.*;
import com.yu.retailops.persistence.FileTaskRepository;
import com.yu.retailops.runtime.*;
import com.yu.retailops.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StageTwoTests {
    final Clock clock = Clock.systemUTC();
    final JsonMapper json = JsonMapper.builder().build();
    static class AskingPlanner implements Planner, InvestigationPlanner {
        public Reply investigate(com.yu.retailops.domain.Investigation.Context c) {
            boolean answered = c.userGoal().contains("UNTRUSTED_USER_ANSWER");
            return new Reply(new Proposal(answered ? Action.NEEDS_HUMAN : Action.ASK_USER,
                    "需要确认历史时间", List.of(), null, List.of(),
                    List.of(answered ? "用户说法仍需日志验证" : "大约什么时候发现异常？"), List.of()), 10, 5);
        }
        public ModelReply next(Planner.Context c) { throw new AssertionError(); }
        public String provider() { return "TEST_DOUBLE_NOT_LLM"; }
        public String modelVersion() { return "stage2-test"; }
    }
    Task await(TaskService service, String id, Status state) throws Exception {
        for (int i=0; i<200; i++) {
            Task task=service.get(id);
            if(task.status==state) return task;
            Thread.sleep(10);
        }
        throw new AssertionError("Did not reach " + state);
    }
    @Test void waitingSurvivesRestartAndAnswerResumesWithCumulativeBudget(@TempDir Path dir) throws Exception {
        var catalog=new FixtureCatalog(json);
        var gateway=new ReadOnlyGateway(catalog,"A001",clock);
        var policy=new ReportPolicy(clock);
        var planner=new AskingPlanner();
        String id;
        try(var repo=new FileTaskRepository(dir,json);
            var runner=new DiagnosisRunner(gateway,policy,clock,8,12,10);
            var service=new TaskService(repo,runner,planner,new OfflinePlanner(policy,true),catalog,gateway,clock)) {
            Task created=service.create(new Input("调查原因","A001","O100","synced","investigation"));
            id=created.id;
            Task waiting=await(service,id,Status.WAITING_USER);
            assertEquals(1,waiting.decisions);
            assertEquals(2,waiting.evidence.size());
            assertNotNull(waiting.agentState.question);
        }
        try(var repo=new FileTaskRepository(dir,json);
            var runner=new DiagnosisRunner(gateway,policy,clock,8,12,10);
            var service=new TaskService(repo,runner,planner,new OfflinePlanner(policy,true),catalog,gateway,clock)) {
            Task waiting=service.get(id);
            assertEquals(Status.WAITING_USER,waiting.status);
            assertThrows(TaskService.ConflictException.class,()->service.resume(id,waiting.version+1,"十分钟前"));
            service.resume(id,waiting.version,"十分钟前");
            assertThrows(TaskService.ConflictException.class,()->service.resume(id,waiting.version,"重复"));
            Task done=await(service,id,Status.NEEDS_HUMAN);
            assertEquals(2,done.decisions);
            assertEquals(2,done.investigation.turns.size());
            assertEquals(1,done.agentState.answers.size());
            assertEquals(20,done.promptTokens);
            assertEquals(2,done.evidence.size());
        }
    }
    @Test void interruptedInvestigationIsPausedAndResumesWithoutLosingTrace(@TempDir Path dir) throws Exception {
        var catalog=new FixtureCatalog(json);var gateway=new ReadOnlyGateway(catalog,"A001",clock);
        var policy=new ReportPolicy(clock);var planner=new AskingPlanner();
        try(var repo=new FileTaskRepository(dir,json);var runner=new DiagnosisRunner(gateway,policy,clock,8,12,10)) {
            Task t=new Task();t.id=UUID.randomUUID().toString();t.input=new Input("why","A001","O100","synced","investigation");
            t.status=Status.PLANNING;t.decisions=2;t.activeMillis=100;t.investigation=new Trace();t.investigation.intent=Intent.DIAGNOSE_CAUSE;
            repo.save(t);
            try(var service=new TaskService(repo,runner,planner,new OfflinePlanner(policy,true),catalog,gateway,clock)) {
                Task paused=service.get(t.id);assertEquals(Status.PAUSED,paused.status);
                service.resume(t.id,paused.version,null);
                Task waiting=await(service,t.id,Status.WAITING_USER);
                assertEquals(3,waiting.decisions);assertTrue(waiting.activeMillis>=100);
            }
        }
    }
    @Test void expiredWaitStops(@TempDir Path dir) throws Exception {
        var catalog=new FixtureCatalog(json);var gateway=new ReadOnlyGateway(catalog,"A001",clock);
        var policy=new ReportPolicy(clock);var planner=new AskingPlanner();
        try(var repo=new FileTaskRepository(dir,json);var runner=new DiagnosisRunner(gateway,policy,clock,8,12,10);
            var service=new TaskService(repo,runner,planner,new OfflinePlanner(policy,true),catalog,gateway,clock)) {
            Task t=service.create(new Input("why","A001","O100","synced","investigation"));
            Task waiting=await(service,t.id,Status.WAITING_USER);
            // Wait for the worker's final snapshot before simulating expiration.
            service.close();
            waiting=repo.get(t.id);waiting.agentState.waitingUntil=Instant.EPOCH;repo.save(waiting);
            Task expired=service.resume(t.id,waiting.version,"昨天");
            assertEquals(Status.NEEDS_HUMAN,expired.status);
            assertEquals("USER_WAIT_EXPIRED",expired.events.get(expired.events.size()-1).code());
        }
    }
}
