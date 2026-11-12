package com.yu.retailops.tool;

import com.yu.retailops.domain.Diagnosis.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded read-only execution; no automatic retry. Drivers must also enforce their own I/O deadlines. */
public final class ToolExecutor implements AutoCloseable {
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"readonly-tool");t.setDaemon(true);return t;});
    public ToolResult execute(ToolRegistry.Definition tool, Input input, List<Evidence> history) {
        Future<ToolResult> future;
        try { future=workers.submit(()->tool.handler().execute(input,List.copyOf(history))); }
        catch(RejectedExecutionException busy) { return error(ResultStatus.ERROR,"TOOL_CAPACITY_EXCEEDED"); }
        try {
            ToolResult result=future.get(tool.timeoutMillis(),TimeUnit.MILLISECONDS);
            if(result==null || result.status()==null || result.outcome()==null || result.data()==null)
                return error(ResultStatus.ERROR,"TOOL_INVALID_RESULT");
            if(result.toString().length()>tool.maxResultCharacters()) return error(ResultStatus.ERROR,"TOOL_RESULT_TOO_LARGE");
            return result;
        } catch(TimeoutException timeout) {return error(ResultStatus.TIMEOUT,"TOOL_TIMEOUT");}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();return error(ResultStatus.ERROR,"TOOL_INTERRUPTED");}
        catch(ExecutionException failed) {return error(ResultStatus.ERROR,"TOOL_EXECUTION_FAILED");}
        finally {if(!future.isDone())future.cancel(true);}
    }
    private ToolResult error(ResultStatus status,String code) {
        return new ToolResult(status,"INDETERMINATE",false,Map.of(),code);
    }
    public void close(){workers.shutdownNow();}
}
