package com.yu.retailops.tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import com.yu.retailops.domain.Diagnosis.*;
import static org.junit.jupiter.api.Assertions.*;
class LogEventTests {
 @TempDir Path root;
 ToolResult query(String log) throws Exception {
  try(var out=new GZIPOutputStream(Files.newOutputStream(root.resolve("b-push-center-server.log.2026-11-11.2.gz")))){out.write(log.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  return new LocalLogAdapter(root,"2026-11-11").definition().handler().execute(new Input("q","A001","123","restore","investigation"),List.of());
 }
 @Test void longLineDoesNotHideLaterStack() throws Exception {
  var r=query("2026-11-11 10:00:00 payload="+"x".repeat(50000)+"\n2026-11-11 10:00:01 order_id=123\njava.sql.SQLException: Connection refused\n\tat demo.Client.call(Client.java:12)\n");
  assertEquals(ResultStatus.PARTIAL,r.status());assertTrue(r.toString().contains("Connection refused"));
  assertTrue(r.toString().contains("lineStart=2"));assertFalse(r.complete());
 }
 @Test void explicitTraceLinksEarlierFailureButThreadDoesNot() throws Exception {
  var r=query("2026-11-11 10:00:00 [worker-1] traceId=trace123\njava.sql.SQLException: Connection refused\n2026-11-11 10:00:01 [worker-1] order_id=123 traceId=trace123\n2026-11-11 10:00:02 [worker-1]\njava.lang.NullPointerException: unrelated\n");
  assertTrue(r.toString().contains("Connection refused"));assertTrue(r.toString().contains("TRACE_ID"));assertFalse(r.toString().contains("unrelated"));
 }
 @Test void conflictingTraceCannotLinkUnidentifiedEvent() throws Exception {
  var r=query("2026-11-11 10:00:00 order_id=123 traceId=trace123\n2026-11-11 10:00:01 order_id=456 traceId=trace123\n2026-11-11 10:00:02 traceId=trace123\njava.sql.SQLException: ambiguous\n");
  assertFalse(r.toString().contains("ambiguous"));
 }
 @Test void sensitiveExceptionDetailsAndPayloadsAreExcluded() {
  var d=LogTextRedactor.diagnostics("java.sql.SQLException: password=secret\njava.lang.RuntimeException: email=a@b.com 13812345678 http://private/a\n{\"customer\":\"Alice\"}\n");
  String s=d.toString();assertFalse(s.contains("secret"));assertFalse(s.contains("a@b.com"));assertFalse(s.contains("13812345678"));assertFalse(s.contains("private"));assertFalse(s.contains("Alice"));
 }
 @Test void unknownExceptionMessageRemainsAvailableForModel() {
  assertTrue(LogTextRedactor.diagnostics("com.vendor.UnfamiliarException: upstream circuit is open").toString().contains("upstream circuit is open"));
 }
 @Test void budgetTruncationDoesNotProduceCompleteEvent() throws Exception {
  var s=LogEventReader.read(new java.io.StringReader("2026-11-11 10:00:00 order_id=123\njava.sql.SQLException: timeout"),50);
  assertTrue(s.truncated());assertTrue(s.events().get(0).truncated());
 }
}
