package io.auralis.desktop.visualization;
/** Frame-time hysteresis; model, audio response and animation clock stay unchanged. */
public final class FrameBudget {
 private int level;private double load;private long changed,goodSince;
 public int sample(double millis,int fps,long now){double ratio=millis/(1000.0/Math.max(1,fps));load=load==0?ratio:load*.9+ratio*.1;if(now-changed<1_500_000_000L)return level;if(load>1.1&&level<3){level++;changed=now;goodSince=0;}else if(load<.65&&level>0){if(goodSince==0)goodSince=now;if(now-goodSince>10_000_000_000L){level--;changed=now;goodSince=0;}}else goodSince=0;return level;}
 public int level(){return level;}public double scale(){return new double[]{1,.85,.65,.5}[level];}public int fps(int requested){return Math.min(requested,new int[]{60,45,30,15}[level]);}
}
