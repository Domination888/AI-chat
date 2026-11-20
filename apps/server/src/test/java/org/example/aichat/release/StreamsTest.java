package org.example.aichat.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamsTest {
 @TempDir Path data;
 private Streams streams()throws Exception{return new Streams(new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline").withProperty("story.data-dir",data.toString())));}
 @Test void interruptWaitsUntilPersistenceFinishesBeforeAllowingRetry()throws Exception{
  var streams=streams();var active=streams.start(1,"conversation","world");var started=new CountDownLatch(1);
  var worker=Executors.newSingleThreadExecutor();
  try{
   var pending=worker.submit(()->{started.countDown();return streams.stopAndAwait("conversation",2000);});assertTrue(started.await(1,TimeUnit.SECONDS));
   assertThrows(TimeoutException.class,()->pending.get(50,TimeUnit.MILLISECONDS));
   assertTrue(active.control().stopped.get());
   // ChatApi calls finish only after its interrupted history row is persisted.
   streams.finish("conversation",active);assertTrue(pending.get(1,TimeUnit.SECONDS));
   var retry=streams.start(1,"conversation","world");streams.finish("conversation",retry);
  }finally{worker.shutdownNow();streams.close();}
 }
 @Test void interruptHasABoundAndDoesNotPretendSlowPersistenceHasFinished()throws Exception{
  var streams=streams();var active=streams.start(1,"conversation","world");
  try{assertFalse(streams.stopAndAwait("conversation",20));assertTrue(active.control().stopped.get());assertFalse(active.completion().isDone());streams.finish("conversation",active);assertTrue(streams.stopAndAwait("conversation",20));}
  finally{streams.close();}
 }
}
