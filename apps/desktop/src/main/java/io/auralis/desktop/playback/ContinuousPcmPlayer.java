package io.auralis.desktop.playback;

import io.auralis.core.audio.FfmpegService;
import io.auralis.core.model.Track;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** One continuous PCM output clock. File boundaries never close the output line. */
public final class ContinuousPcmPlayer implements AutoCloseable {
 public record Options(double crossfadeSeconds,double volume,double rate,double balance,boolean eq,double[] gains,Map<String,Double> trackGainDb,Set<String> noFadeAlbums){public Options{gains=gains.clone();trackGainDb=Map.copyOf(trackGainDb);noFadeAlbums=Set.copyOf(noFadeAlbums);}}
 public interface Listener { void track(Track track,int index);void state(boolean ready,boolean playing,double seconds,double duration);void finished(Track track);void spectrum(float[] magnitudes,double seconds);void error(String message); }
 public interface Sink extends AutoCloseable {void write(byte[] bytes,int length);long playedFrames();void pause(boolean pause);void drain();void close();}
 @FunctionalInterface public interface SinkFactory {Sink open(float sampleRate)throws Exception;}
 private static final int RATE=48000,FRAMES=1024;
 private final FfmpegService ffmpeg;private final Path cache;private final ExecutorService worker;private final Listener listener;private final SinkFactory sinkFactory;
 private final Object pauseLock=new Object();private volatile boolean closed,paused;private volatile Options options;private volatile List<Track> queue;private volatile int index;private volatile PlaybackService.Repeat repeat;private volatile boolean shuffle;private volatile Sink sink;private Future<?> job;private volatile long queueRevision;
 private final Map<String,CompletableFuture<Path>> prepared=new ConcurrentHashMap<>();
 private final Queue<Future<?>> decodingJobs=new ConcurrentLinkedQueue<>();
 public ContinuousPcmPlayer(FfmpegService ffmpeg,Path cache,ExecutorService worker,Listener listener){this(ffmpeg,cache,worker,listener,ContinuousPcmPlayer::soundSink);}
 public ContinuousPcmPlayer(FfmpegService ffmpeg,Path cache,ExecutorService worker,Listener listener,SinkFactory factory){this.ffmpeg=ffmpeg;this.cache=cache;this.worker=worker;this.listener=listener;this.sinkFactory=factory;}
 public void start(List<Track> tracks,int selected,double seconds,boolean autoplay,Options config,PlaybackService.Repeat loop,boolean random){queue=List.copyOf(tracks);index=selected;options=config;repeat=loop;shuffle=random;paused=!autoplay;job=worker.submit(()->run(seconds));}
 public synchronized void update(List<Track> tracks,int selected,PlaybackService.Repeat loop,boolean random){if(!queue.equals(tracks)||index!=selected||repeat!=loop||shuffle!=random){queue=List.copyOf(tracks);index=selected;repeat=loop;shuffle=random;queueRevision++;}}
 public void configure(Options value){options=value;}
 public int currentIndex(){return index;}
 public void pause(boolean value){paused=value;Sink output=sink;if(output!=null){output.pause(value);listener.state(true,!value,position,trackDuration);}synchronized(pauseLock){pauseLock.notifyAll();}}
 private String key(Track track)throws IOException{return UUID.nameUUIDFromBytes((track.path().toAbsolutePath()+"|"+Files.size(track.path())+"|"+Files.getLastModifiedTime(track.path()).toMillis()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
 private CompletableFuture<Path> prepare(Track track)throws IOException {
  String cacheKey=key(track);
  return prepared.computeIfAbsent(cacheKey,k->{
   CompletableFuture<Path> result=new CompletableFuture<>();
   Future<?> task=worker.submit(()->{Path temp=null;try{
    if(closed)throw new InterruptedIOException("Воспроизведение остановлено");
    Files.createDirectories(cache);Path destination=cache.resolve("pcm48-"+cacheKey+".wav");
    if(Files.isRegularFile(destination)&&Files.size(destination)>44){result.complete(destination);return;}
    long expected=(long)Math.min(Long.MAX_VALUE/4,Math.max(0,track.durationSeconds())*192000);
    if(Files.getFileStore(cache).getUsableSpace()<expected+32L*1024*1024)throw new IOException("Недостаточно места для непрерывного воспроизведения");
    temp=Files.createTempFile(cache,"pcm-",".wav");
    FfmpegService.run(List.of(ffmpeg.executable(),"-nostdin","-v","error","-i",track.path().toString(),"-map","0:a:0","-vn","-ac","2","-ar","48000","-c:a","pcm_s16le","-y",temp.toString()),Duration.ofMinutes(30));
    try{Files.move(temp,destination);}catch(FileAlreadyExistsException ignored){}
    Set<Path> active=new HashSet<>();active.add(destination);prepared.values().forEach(f->{if(f.isDone()&&!f.isCompletedExceptionally()){Path p=f.getNow(null);if(p!=null)active.add(p);}});
    try{trimCache(cache,active,512L*1024*1024);}catch(IOException ignored){}
    result.complete(destination);
   }catch(Throwable error){result.completeExceptionally(error);}finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}});
   decodingJobs.add(task);if(closed){task.cancel(true);result.cancel(false);}return result;
  });
 }
 private static final class Input implements AutoCloseable {final AudioInputStream stream;final Path path;final long total;long frame;Input(Path path,double start)throws Exception{this.path=path;stream=AudioSystem.getAudioInputStream(path.toFile());total=stream.getFrameLength();long skip=Math.min(total,Math.max(0,(long)(start*RATE)));long left=skip*4;while(left>0){long n=stream.skip(left);if(n<=0)break;left-=n;}frame=skip-left/4;}int read(byte[] buffer,int frames)throws IOException{int wanted=(int)Math.min((long)frames*4,(total-frame)*4),count=0;while(count<wanted){int n=stream.read(buffer,count,wanted-count);if(n<0)break;count+=n;}frame+=count/4;return count/4;}public void close()throws IOException{stream.close();}}
 private int nextIndex(){List<Track> tracks=queue;int at=index;if(repeat==PlaybackService.Repeat.ONE)return at;if(shuffle&&tracks.size()>1){int value=ThreadLocalRandom.current().nextInt(tracks.size()-1);return value>=at?value+1:value;}return at+1<tracks.size()?at+1:repeat==PlaybackService.Repeat.ALL&&!tracks.isEmpty()?0:-1;}
 public static String albumKey(Track track){return track.artist().strip().toLowerCase(Locale.ROOT)+"\n"+track.album().strip().toLowerCase(Locale.ROOT);}
 private void waitForPlay()throws InterruptedException{synchronized(pauseLock){while(paused&&!closed){listener.state(true,false,position,trackDuration);pauseLock.wait(100);}}}
 private volatile double position,trackDuration;private long written,trackStart;private double startOffset;private long lastStatus;private final Spectrum spectrum=new Spectrum();private final Equalizer equalizer=new Equalizer();
 private void run(double start){Input input=null,nextInput=null;try{
  Track track=queue.get(index);CompletableFuture<Path> current=prepare(track);int next=nextIndex();long plannedRevision=queueRevision;String plannedId=next<0?null:queue.get(next).id();CompletableFuture<Path> pending=next<0?null:prepare(queue.get(next));input=new Input(current.get(),start);if(pending!=null)pending.get();if(closed)return;sink=sinkFactory.open((float)(RATE*options.rate()));sink.pause(paused);startOffset=input.frame/(double)RATE;position=startOffset;trackDuration=input.total/(double)RATE;listener.track(track,index);listener.state(true,!paused,startOffset,trackDuration);
  byte[] first=new byte[FRAMES*4],second=new byte[FRAMES*4],output=new byte[FRAMES*4];
  while(!closed){
   waitForPlay();if(closed)break;
   int desiredNext=plannedRevision==queueRevision?next:nextIndex();plannedRevision=queueRevision;Track nextTrack=desiredNext>=0&&desiredNext<queue.size()?queue.get(desiredNext):null;
   if(nextTrack==null){if(nextInput!=null){nextInput.close();nextInput=null;}next=-1;pending=null;plannedId=null;}
   else if(pending==null||next!=desiredNext||!nextTrack.id().equals(plannedId)){if(nextInput!=null){nextInput.close();nextInput=null;}next=desiredNext;plannedId=nextTrack.id();pending=prepare(nextTrack);}
   long fade=0;if(nextTrack!=null&&repeat!=PlaybackService.Repeat.ONE&&options.crossfadeSeconds()>0&&!options.noFadeAlbums().contains(albumKey(track))&&!options.noFadeAlbums().contains(albumKey(nextTrack))&&pending!=null&&pending.isDone()){
    if(nextInput==null)nextInput=new Input(pending.get(),0);fade=Math.min((long)(options.crossfadeSeconds()*RATE),Math.min(input.total/2,nextInput.total/2));
   }
   long before=input.frame;int count=input.read(first,FRAMES);if(count<=0){
    Track ended=track;listener.finished(ended);int selected=next;if(selected<0){sink.drain();position=trackDuration;listener.state(true,false,position,trackDuration);break;}
    Track selectedTrack=queue.get(selected);if(nextInput==null||next!=selected){if(nextInput!=null)nextInput.close();nextInput=new Input(prepare(selectedTrack).get(),0);}Path endedPath=input.path;input.close();input=nextInput;nextInput=null;if(!endedPath.equals(input.path))prepared.entrySet().removeIf(e->e.getValue().isDone()&&!e.getValue().isCompletedExceptionally()&&endedPath.equals(e.getValue().getNow(null)));index=selected;track=selectedTrack;trackStart=written;startOffset=input.frame/(double)RATE;position=startOffset;trackDuration=input.total/(double)RATE;listener.track(track,index);next=nextIndex();plannedRevision=queueRevision;plannedId=next<0?null:queue.get(next).id();pending=next<0?null:prepare(queue.get(next));continue;
   }
   int fadeStart=(int)Math.max(0,input.total-fade-before);boolean overlap=fade>0&&before+count>input.total-fade&&nextInput!=null;
   int mixedFrames=overlap?count-Math.min(count,fadeStart):0;if(mixedFrames>0){int read=nextInput.read(second,mixedFrames);if(read!=mixedFrames)throw new IOException("Следующий трек завершился во время перехода");}
   Options config=options;double gain=Math.pow(10,config.trackGainDb().getOrDefault(track.id(),0.0)/20),nextGain=nextTrack==null?1:Math.pow(10,config.trackGainDb().getOrDefault(nextTrack.id(),0.0)/20);equalizer.configure(config.gains(),config.eq());
   for(int frame=0;frame<count;frame++){
    double blend=overlap&&frame>=fadeStart?Math.clamp((before+frame-(input.total-fade))/(double)fade,0,1):0;
    double mono=0;
    for(int channel=0;channel<2;channel++){double value=sample(first,frame,channel)*gain;if(blend>0)value=value*(1-blend)+sample(second,frame-fadeStart,channel)*nextGain*blend;value=equalizer.apply(value,channel)*config.volume()*(channel==0?Math.min(1,1-config.balance()):Math.min(1,1+config.balance()));value=Math.clamp(value,-.999969,.999969);mono+=value;int pcm=(int)Math.round(value*32767),offset=frame*4+channel*2;output[offset]=(byte)pcm;output[offset+1]=(byte)(pcm>>8);}
    spectrum.add(mono/2);
   }
   sink.write(output,count*4);written+=count;long queued=Math.max(0,written-sink.playedFrames());position=Math.clamp(startOffset+(written-trackStart-queued)/(double)RATE,0,trackDuration);long now=System.nanoTime();if(now-lastStatus>50_000_000){lastStatus=now;listener.state(true,!paused,position,trackDuration);listener.spectrum(spectrum.magnitudes(),position);}
  }
 }catch(Exception e){if(!closed)listener.error("Непрерывный PCM-плеер: "+(e.getCause()==null?e.getMessage():e.getCause().getMessage()));}finally{try{if(input!=null)input.close();if(nextInput!=null)nextInput.close();}catch(IOException ignored){}Sink output=sink;if(output!=null)output.close();}}
 static synchronized void trimCache(Path directory,Set<Path> active,long limit)throws IOException{
  List<Path> files;try(var entries=Files.list(directory)){files=entries.filter(p->p.getFileName().toString().matches("pcm48-[a-f0-9-]{36}\\.wav")&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).sorted(Comparator.comparingLong(p->{try{return Files.getLastModifiedTime(p).toMillis();}catch(IOException e){return Long.MAX_VALUE;}})).toList();}
  long total=0;for(Path file:files)total+=Files.size(file);
  for(Path file:files){if(total<=limit)break;if(active.contains(file))continue;try{long size=Files.size(file);if(Files.deleteIfExists(file))total-=size;}catch(IOException ignored){/* An open decoder retains its cache until a later pass. */}}
 }
 private static double sample(byte[] bytes,int frame,int channel){int i=frame*4+channel*2;return (short)((bytes[i]&255)|(bytes[i+1]<<8))/32768.0;}
 public void close(){closed=true;paused=false;synchronized(pauseLock){pauseLock.notifyAll();}Sink output=sink;if(output!=null)output.close();if(job!=null)job.cancel(true);decodingJobs.forEach(task->task.cancel(true));decodingJobs.clear();}
 private static Sink soundSink(float rate)throws Exception{AudioFormat format=new AudioFormat(rate,16,2,true,false);SourceDataLine line=AudioSystem.getSourceDataLine(format);line.open(format,8192);line.start();return new Sink(){public void write(byte[] b,int n){int offset=0;while(offset<n&&line.isOpen()){int written=line.write(b,offset,n-offset);if(written<=0)break;offset+=written;}}public long playedFrames(){return line.getLongFramePosition();}public void pause(boolean pause){if(pause)line.stop();else line.start();}public void drain(){line.drain();}public void close(){line.stop();line.flush();line.close();}};}
 private static final class Spectrum{final double[] data=new double[1024];int cursor;void add(double x){data[cursor++]=x;if(cursor==1024)cursor=0;}float[] magnitudes(){double[] re=new double[1024],im=new double[1024];for(int i=0;i<1024;i++)re[i]=data[(cursor+i)%1024]*(.5-.5*Math.cos(2*Math.PI*i/1023));for(int i=1,j=0;i<1024;i++){int bit=512;while((j&bit)!=0){j^=bit;bit>>=1;}j^=bit;if(i<j){double t=re[i];re[i]=re[j];re[j]=t;}}for(int length=2;length<=1024;length*=2)for(int start=0;start<1024;start+=length)for(int k=0;k<length/2;k++){double angle=-2*Math.PI*k/length,wr=Math.cos(angle),wi=Math.sin(angle);int a=start+k,b=a+length/2;double tr=wr*re[b]-wi*im[b],ti=wr*im[b]+wi*re[b];re[b]=re[a]-tr;im[b]=im[a]-ti;re[a]+=tr;im[a]+=ti;}float[] result=new float[512];for(int i=0;i<512;i++)result[i]=(float)Math.max(-100,20*Math.log10(Math.max(1e-9,Math.hypot(re[i],im[i])*4/1024)));return result;}}
 private static final class Equalizer{final double[][] z1=new double[2][10],z2=new double[2][10];final double[][] coefficients=new double[10][5];double[] previous=new double[10];boolean enabled;void configure(double[] gains,boolean enable){enabled=enable;if(Arrays.equals(previous,gains))return;previous=gains.clone();double[] frequencies={31.25,62.5,125,250,500,1000,2000,4000,8000,16000};for(int b=0;b<10;b++){double a=Math.pow(10,gains[b]/40),w=2*Math.PI*frequencies[b]/RATE,alpha=Math.sin(w)/(2*1.2),d=1+alpha/a;coefficients[b]=new double[]{(1+alpha*a)/d,-2*Math.cos(w)/d,(1-alpha*a)/d,-2*Math.cos(w)/d,(1-alpha/a)/d};}}double apply(double value,int channel){if(!enabled)return value;for(int b=0;b<10;b++){double[] c=coefficients[b];if(previous[b]==0)continue;double out=c[0]*value+z1[channel][b];z1[channel][b]=c[1]*value-c[3]*out+z2[channel][b];z2[channel][b]=c[2]*value-c[4]*out;value=out;}return value;}}
}

