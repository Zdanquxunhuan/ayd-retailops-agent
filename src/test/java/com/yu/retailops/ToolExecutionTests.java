package com.yu.retailops;

import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.tool.*;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionTests {
    final Input input=new Input("query","A001","O100",null,"investigation");
    ToolRegistry.Definition tool(long timeout,int limit,ToolRegistry.Handler handler){
        return new ToolRegistry.Definition("test.query","v1","test read-only",timeout,limit,true,handler);
    }
    @Test void timeoutAndExceptionNeverBecomeNotFound(){
        try(var executor=new ToolExecutor()){
            var timeout=executor.execute(tool(20,1000,(i,h)->{Thread.sleep(10000);return null;}),input,List.of());
            assertEquals(ResultStatus.TIMEOUT,timeout.status());assertFalse(timeout.complete());
            var failure=executor.execute(tool(1000,1000,(i,h)->{throw new IllegalStateException("secret connection string");}),input,List.of());
            assertEquals("TOOL_EXECUTION_FAILED",failure.errorCode());assertFalse(failure.toString().contains("secret"));
        }
    }
    @Test void oversizedAndMalformedResultsAreRejected(){
        try(var executor=new ToolExecutor()){
            assertEquals("TOOL_INVALID_RESULT",executor.execute(tool(1000,1000,(i,h)->null),input,List.of()).errorCode());
            assertEquals("TOOL_RESULT_TOO_LARGE",executor.execute(tool(1000,100,(i,h)->new ToolResult(ResultStatus.SUCCESS,"FOUND",true,Map.of("text","x".repeat(200)),null)),input,List.of()).errorCode());
        }
    }
    @Test void authorizationPrecedesHandlerAndSourceIsServerOwned(){
        AtomicInteger calls=new AtomicInteger();
        var definition=tool(1000,1000,(i,h)->{calls.incrementAndGet();return new ToolResult(ResultStatus.SUCCESS,"FOUND",true,Map.of(),null);});
        try(var gateway=new ReadOnlyGateway(new ToolRegistry(List.of(definition)),"A001",Clock.systemUTC())){
            var wrong=new Input("query","B002","O100",null,"investigation");
            assertThrows(SecurityException.class,()->gateway.invoke(wrong,Decision.call("test.query",wrong,"query"),List.of()));
            assertThrows(SecurityException.class,()->gateway.invoke(input,Decision.call("unknown",input,"query"),List.of()));
            assertEquals(0,calls.get());
            assertTrue(gateway.invoke(input,Decision.call("test.query",input,"query"),List.of()).simulated());
            assertEquals(1,calls.get());assertEquals("test.query",gateway.definitions().get(0).name());
        }
    }
    @Test void duplicateRegistrationIsRejected(){
        var d=tool(1000,1000,(i,h)->null);
        assertThrows(IllegalArgumentException.class,()->new ToolRegistry(List.of(d,d)));
    }
}
