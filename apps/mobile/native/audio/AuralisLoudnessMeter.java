// Generated from audio/core LoudnessMeter; keep algorithms in sync.
package expo.modules.audio;
import java.util.*;

/** Streaming BS.1770 K-weighting, 400 ms blocks / 100 ms hop and two-stage gate. */
public final class AuralisLoudnessMeter {
 public static final class Result { private final double integratedLufs,samplePeakDb; public Result(double lufs,double peak){integratedLufs=lufs;samplePeakDb=peak;} public double integratedLufs(){return integratedLufs;} public double samplePeakDb(){return samplePeakDb;} }
 private final int channels,window,hop;private final Filter[] shelf,highpass;private final double[] ring;private final List<Double> blocks=new ArrayList<>();private long frames;private int at;private double sum,all,peak;
 public AuralisLoudnessMeter(int rate,int channels){if(rate<8000||rate>384000||channels<1||channels>16)throw new IllegalArgumentException("Invalid PCM format");this.channels=channels;window=(int)Math.round(rate*.4);hop=(int)Math.round(rate*.1);ring=new double[window];shelf=new Filter[channels];highpass=new Filter[channels];for(int i=0;i<channels;i++){shelf[i]=shelf(rate);highpass[i]=highpass(rate);}}
 public void accept(float[] frame){double energy=0;for(int c=0;c<channels;c++){double input=Float.isFinite(frame[c])?frame[c]:0;peak=Math.max(peak,Math.abs(input));double value=highpass[c].apply(shelf[c].apply(input));if(channels>3&&c==3)continue;energy+=value*value*(c>=4?1.41:1);}all+=energy;sum+=energy-ring[at];ring[at]=energy;at=(at+1)%window;frames++;if(frames>=window&&(frames-window)%hop==0)blocks.add(sum/window);}
 public Result result(){if(frames==0)return new Result(-120,-120);List<Double> values=blocks.isEmpty()?Collections.singletonList(all/frames):blocks;double absolute=Math.pow(10,(-70+.691)/10);double sum=0;int count=0;for(double e:values)if(e>=absolute){sum+=e;count++;}if(count==0)return new Result(-120,db(peak));double gate=Math.max(absolute,sum/count*.1);sum=0;count=0;for(double e:values)if(e>=gate){sum+=e;count++;}return new Result(count==0?-120:-.691+10*Math.log10(sum/count),db(peak));}
 private static double db(double x){return x>0?Math.max(-120,20*Math.log10(x)):-120;}
 private static Filter shelf(double rate){double k=Math.tan(Math.PI*1681.974450955533/rate),q=.7071752369554196,vh=Math.pow(10,3.999843853973347/20),vb=Math.pow(vh,.4996667741545416),a=1+k/q+k*k;return new Filter((vh+vb*k/q+k*k)/a,2*(k*k-vh)/a,(vh-vb*k/q+k*k)/a,2*(k*k-1)/a,(1-k/q+k*k)/a);}
 private static Filter highpass(double rate){double k=Math.tan(Math.PI*38.13547087602444/rate),q=.5003270373238773,a=1+k/q+k*k;return new Filter(1,-2,1,2*(k*k-1)/a,(1-k/q+k*k)/a);}
 private static final class Filter{final double b0,b1,b2,a1,a2;double z1,z2;Filter(double b0,double b1,double b2,double a1,double a2){this.b0=b0;this.b1=b1;this.b2=b2;this.a1=a1;this.a2=a2;}double apply(double x){double y=b0*x+z1;z1=b1*x-a1*y+z2;z2=b2*x-a2*y;return y;}}
}
