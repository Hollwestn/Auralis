import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { fragmentShader, vertexShader, visualModes } from '../src/visuals/shaders.ts';
import { themes } from '../src/core/theme.ts';
const { chromium } = await import(pathToFileURL(path.resolve(process.argv[2])).href);
const browser = await chromium.launch({executablePath:process.argv[3],headless:true});
try {
  const page=await browser.newPage({viewport:{width:1920,height:1800}});
  await page.setContent('<body style="margin:20px;background:#08090b;color:#eee;font:12px Arial"><h2>Auralis 0.6 · 8 scenes × 8 palettes</h2><main style="display:grid;grid-template-columns:repeat(8,1fr);gap:12px"></main></body>');
  const result=await page.evaluate(({fragment,vertex,modes,themes})=>{
    const source=document.createElement('canvas');source.width=256;source.height=180;
    const gl=source.getContext('webgl',{preserveDrawingBuffer:true});
    if(!gl)throw new Error('No WebGL');
    const compile=(kind,text)=>{const s=gl.createShader(kind);gl.shaderSource(s,text);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw new Error(gl.getShaderInfoLog(s));return s;};
    const program=gl.createProgram();gl.attachShader(program,compile(gl.VERTEX_SHADER,vertex));gl.attachShader(program,compile(gl.FRAGMENT_SHADER,fragment));gl.linkProgram(program);if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw new Error(gl.getProgramInfoLog(program));gl.useProgram(program);
    const buffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,buffer);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,-1,1,1,-1,1,1]),gl.STATIC_DRAW);
    const position=gl.getAttribLocation(program,'position');gl.enableVertexAttribArray(position);gl.vertexAttribPointer(position,2,gl.FLOAT,false,0,0);
    const loc=name=>gl.getUniformLocation(program,name);gl.uniform2f(loc('u_resolution'),256,180);gl.uniform1f(loc('u_quality'),2);gl.uniform1f(loc('u_time'),5);gl.uniform1f(loc('u_intensity'),1);
    const rows=[];
    for(let mode=0;mode<modes.length;mode++){
      let original=null;
      for(const [name,{accent}] of Object.entries(themes)){
        const rgb=accent.slice(1).match(/../g).map(value=>parseInt(value,16)/255);
        gl.uniform3f(loc('u_accent'),...rgb);gl.uniform1f(loc('u_mode'),mode);gl.drawArrays(gl.TRIANGLES,0,6);
        const pixels=new Uint8Array(256*180*4);gl.readPixels(0,0,256,180,gl.RGBA,gl.UNSIGNED_BYTE,pixels);if(gl.getError())throw new Error('GL error');
        let energy=0,difference=0,chroma=0;
        for(let n=0;n<pixels.length;n+=4){energy+=pixels[n]+pixels[n+1]+pixels[n+2];chroma+=Math.abs(pixels[n]-pixels[n+1])+Math.abs(pixels[n+1]-pixels[n+2]);if(original)for(let k=0;k<3;k++)difference+=Math.abs(pixels[n+k]-original[n+k]);}
        if(!energy)throw new Error(`${modes[mode]} / ${name} is empty`);
        if(original&&difference<energy*.015)throw new Error(`${modes[mode]} does not use ${name}`);
        if(name==='Monochrome'&&chroma>256*180*2)throw new Error('Colored Monochrome');
        if(!original)original=pixels;
        const card=document.createElement('section');const label=document.createElement('p');label.textContent=modes[mode]+' · '+name;
        const canvas=document.createElement('canvas');canvas.width=256;canvas.height=180;canvas.style.width='100%';canvas.getContext('2d').drawImage(source,0,0);card.append(label,canvas);document.querySelector('main').append(card);
        rows.push({mode:modes[mode],theme:name,energy,differenceFromObsidian:difference,monochrome:name==='Monochrome'?true:null});
      }
    }
    return rows;
  },{fragment:fragmentShader,vertex:vertexShader,modes:visualModes,themes});
  const output=path.resolve(process.argv[4]);await page.screenshot({path:output,fullPage:true});await fs.writeFile(output.replace(/\.png$/,'.json'),JSON.stringify(result,null,2));console.log(`AURALIS_PALETTES_OK ${result.length}`);
} finally {await browser.close();}
