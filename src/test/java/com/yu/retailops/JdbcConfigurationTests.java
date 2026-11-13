package com.yu.retailops;
import com.yu.retailops.application.JdbcToolResources;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
class JdbcConfigurationTests {
 @Test void defaultsNeverCreateDbAdapters(){try(var r=new JdbcToolResources(new MockEnvironment(),"A001")){assertTrue(r.definitions().isEmpty());}}
 @Test void unknownModesAndMissingReadOnlyConfirmationFailClosed(){
  assertThrows(IllegalArgumentException.class,()->new JdbcToolResources(new MockEnvironment().withProperty("omniops.jdbc.local.mode","typo"),"A001"));
  assertThrows(IllegalArgumentException.class,()->new JdbcToolResources(new MockEnvironment().withProperty("omniops.jdbc.local.mode","jdbc"),"A001"));
 }
 @Test void storeMismatchCannotCreateRouting(){
  var env=new MockEnvironment().withProperty("omniops.jdbc.local.mode","jdbc").withProperty("omniops.jdbc.local.readonly-account-confirmed","true").withProperty("omniops.jdbc.local.store-id","C100001");
  assertThrows(IllegalArgumentException.class,()->new JdbcToolResources(env,"C200002"));
 }
}
