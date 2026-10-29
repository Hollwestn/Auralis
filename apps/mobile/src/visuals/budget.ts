/** Hysteresis avoids quality oscillation. Manual presets bypass adaptive changes. */
export class FrameBudget {
  level=0;private load=0;private since=0;private goodSince=0;
  sample(costMs:number,elapsedMs:number,fps:number,now:number,lowPower=false,thermal=0){
    const interval=1000/Math.max(1,fps),ratio=Math.max(costMs/interval,elapsedMs/(interval*1.6));
    this.load=this.load===0?ratio:this.load*.9+ratio*.1;
    const floor=thermal>=6?3:thermal>=4||lowPower?2:0;
    if(this.level<floor){this.level=floor;this.since=now;this.goodSince=0;return this.level;}
    if(now-this.since<1500)return this.level;
    if(this.load>1.1&&this.level<3){this.level++;this.since=now;this.goodSince=0;}
    else if(this.load<.65&&this.level>floor){if(!this.goodSince)this.goodSince=now;if(now-this.goodSince>10000){this.level--;this.since=now;this.goodSince=0;}}
    else this.goodSince=0;
    return this.level;
  }
}
export const budgetScale=[1,.85,.65,.5];
export const budgetFps=(requested:number,level:number)=>Math.min(requested,[60,45,30,15][level]||60);
export const graphicsBudgetTelemetry={level:0};
