package io.auralis.desktop.playback;
import io.auralis.core.audio.*;
import io.auralis.core.model.Track;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class ContinuousPcmPlayerTest {
 @TempDir Path root;
 @Test void oneOutputClockJoinsFilesAtExactPcmBoundary()throws Exception{assertFrames(0,96000);}
 @Test void crossfadeOverlapsPcmAndNeverExceedsSampleRange()throws Exception{assertFrames(.2,86400);}
 @Test void cacheBudgetEvictsOnlyInactiveGeneratedPcmFiles()throws Exception{
  Path active=root.resolve("pcm48-"+UUID.randomUUID()+".wav"),old=root.resolve("pcm48-"+UUID.randomUUID()+".wav"),original=root.resolve("my-original.wav");
  Files.write(active,new byte[80]);Files.write(old,new byte[80]);Files.write(original,new byte[80]);Files.setLastModifiedTime(old,java.nio.file.attribute.FileTime.fromMillis(1));
  ContinuousPcmPlayer.trimCache(root,Set.of(active),100);assertTrue(Files.exists(active));assertFalse(Files.exists(old));assertTrue(Files.exists(original));
 }
 private void assertFrames(double fade,long expected)throws Exception{
  var ffmpeg=new FfmpegService();Assumptions.assumeTrue(ffmpeg.available());List<Track> tracks=new ArrayList<>();for(int i=0;i<2;i++){Path path=root.resolve("tone"+i+".wav");FfmpegService.run(List.of(ffmpeg.executable(),"-nostdin","-v","error","-f","lavfi","-i","sine=frequency="+(440+i*110)+":duration=1:sample_rate=48000","-ac","2","-y",path.toString()),Duration.ofSeconds(20));tracks.add(new Track("t"+i,"Tone","A","Album","",path,1,""));}
  AtomicLong frames=new AtomicLong();AtomicInteger opens=new AtomicInteger();List<String> transitions=new CopyOnWriteArrayList<>();CountDownLatch finished=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();
  try(var worker=Executors.newVirtualThreadPerTaskExecutor()){
   var player=new ContinuousPcmPlayer(ffmpeg,root.resolve("cache"),worker,new ContinuousPcmPlayer.Listener(){public void track(Track t,int index){transitions.add(t.id());}public void state(boolean ready,boolean playing,double p,double d){if(!playing&&p>=d&&d>0)finished.countDown();}public void finished(Track t){}public void spectrum(float[] m,double p){}public void error(String text){error.set(text);finished.countDown();}},rate->{opens.incrementAndGet();return new ContinuousPcmPlayer.Sink(){public void write(byte[] data,int length){assertEquals(0,length%4);frames.addAndGet(length/4);for(int i=0;i<length;i+=2){int value=(short)((data[i]&255)|(data[i+1]<<8));assertTrue(value>=-32768&&value<=32767);}}public long playedFrames(){return frames.get();}public void pause(boolean p){}public void drain(){}public void close(){}};});
   player.start(tracks,0,0,true,new ContinuousPcmPlayer.Options(fade,1,1,0,false,new double[10],Map.of(),Set.of()),PlaybackService.Repeat.OFF,false);assertTrue(finished.await(30,TimeUnit.SECONDS));player.close();assertNull(error.get());assertEquals(1,opens.get());assertEquals(expected,frames.get());assertEquals(List.of("t0","t1"),transitions);
  }
 }
}

