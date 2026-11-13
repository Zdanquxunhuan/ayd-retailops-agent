package com.yu.retailops;
import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.domain.Investigation.*;
import com.yu.retailops.runtime.InvestigationPolicy;
import com.yu.retailops.tool.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LogEvidencePolicyTests {
 void validate(List<?> lines) {
  Clock clock=Clock.systemUTC();Task task=new Task();task.investigation=new Trace();
  task.evidence.add(new Evidence("E1",ReadOnlyGateway.LOGS,new Arguments("A001","123"),
   new ToolResult(ResultStatus.PARTIAL,"LOG_SNAPSHOT",false,Map.of("lines",lines),null),clock.instant(),clock.instant().plusSeconds(60),false));
  var h=new Hypothesis("H1","数据库连接可能失败",HypothesisStatus.SUPPORTED,List.of("E1"),List.of(),List.of("仍缺业务状态"));
  var proposal=new Proposal(Action.NEEDS_HUMAN,"继续核实",List.of(h),null,List.of(),List.of("缺少线上状态"));
  try(var gateway=new ReadOnlyGateway(new ToolRegistry(List.of()),"A001",clock)) {new InvestigationPolicy(gateway,clock).validate(proposal,task);}
 }
 @Test void intactPositiveEventsSurviveIncompleteSearch() {assertDoesNotThrow(()->validate(List.of(Map.of("eventComplete",true,"correlation","ORDER_ID"))));}
 @Test void partialEventCannotSupportCause() {assertThrows(IllegalArgumentException.class,()->validate(List.of(Map.of("eventComplete",false,"correlation","ORDER_ID"))));}
 @Test void noRecordsCannotSupportCause() {assertThrows(IllegalArgumentException.class,()->validate(List.of()));}
 @Test void threadOnlyCorrelationCannotSupportCause() {assertThrows(IllegalArgumentException.class,()->validate(List.of(Map.of("eventComplete",true,"correlation","THREAD"))));}
}
