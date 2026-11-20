package org.example.aichat.release;

import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.*;
import java.util.*;
import jakarta.annotation.PreDestroy;

@Component
public class Streams {
  public record Active(int user,String world,OpenAiTransport.Control control,CompletableFuture<Void> completion){}
  private final ConcurrentHashMap<String,Active> streams=new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String,java.util.concurrent.locks.ReentrantLock> locks=new ConcurrentHashMap<>();
  private final Semaphore permits;private final ExecutorService executor;
  public Streams(RuntimeMode mode){int max=mode.cloud()?mode.number("max-streams",20):1;permits=new Semaphore(max);executor=Executors.newFixedThreadPool(max,r->{var t=new Thread(r,"story-stream");t.setDaemon(true);return t;});}
  public java.util.concurrent.locks.ReentrantLock lock(String world){return locks.computeIfAbsent(world,k->new java.util.concurrent.locks.ReentrantLock());}
  public synchronized Active start(int user,String conv,String world){
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
    while(streams.values().stream().anyMatch(a->(a.user()==user||Objects.equals(a.world(),world))&&a.control().stopped.get())&&System.nanoTime()<deadline){try{wait(20);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
    if(streams.values().stream().anyMatch(a->a.user()==user || Objects.equals(a.world(),world)) || !permits.tryAcquire())throw new ResponseStatusException(HttpStatus.CONFLICT,"已有故事正在回复，请先停止或稍后重试");
    var active=new Active(user,world,new OpenAiTransport.Control(),new CompletableFuture<>());streams.put(conv,active);return active;
  }
  public void execute(Runnable task){executor.execute(task);}
  public synchronized void finish(String conv,Active active){if(streams.remove(conv,active)){permits.release();notifyAll();}active.completion().complete(null);}
  public void stop(String conv){stop(conv,CancelCause.OTHER_OPERATION);}
  public void stop(String conv,CancelCause cause){var a=streams.get(conv);if(a!=null)a.control().stop(cause);}
  public boolean stopAndAwait(String conv,long timeoutMillis){
    var active=streams.get(conv);if(active==null)return true;
    active.control().stop(CancelCause.USER_STOP);
    try{active.completion().get(timeoutMillis,TimeUnit.MILLISECONDS);return true;}
    catch(TimeoutException e){return false;}
    catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
    catch(ExecutionException e){throw new IllegalStateException(e.getCause());}
  }
  public void stopWorld(String world){streams.values().stream().filter(a->Objects.equals(world,a.world())).forEach(a->a.control().stop());}
  public void stopUser(int user){stopUser(user,CancelCause.OTHER_OPERATION);}
  public void stopUser(int user,CancelCause cause){streams.values().stream().filter(a->a.user()==user).forEach(a->a.control().stop(cause));}
  public <T>T mutate(String world,Callable<T> task){stopWorld(world);var lock=lock(world);lock.lock();try{return task.call();}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}finally{lock.unlock();}}
  @PreDestroy public void close(){streams.values().forEach(a->a.control().stop(CancelCause.APPLICATION_SHUTDOWN));executor.shutdownNow();}
}
