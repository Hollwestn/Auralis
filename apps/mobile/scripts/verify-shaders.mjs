import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { fragmentShader, vertexShader, visualModes } from '../src/visuals/shaders.ts';

const { chromium } = await import(pathToFileURL(path.resolve(process.argv[2])).href);
const browser = await chromium.launch({ executablePath: process.argv[3], headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1240 }, deviceScaleFactor: 1 });
  await page.setContent('<html><body style="margin:0;background:#08090b;color:#eee;font:16px Arial;padding:24px"><h2>Auralis · GLSL verification · zero audio input</h2><main style="display:grid;grid-template-columns:repeat(4,1fr);gap:16px"></main></body></html>');
  const result = await page.evaluate(({ fragment, vertex, modes }) => modes.map((name, index) => {
    const card = document.createElement('section'); const label = document.createElement('p'); label.textContent = name; card.append(label);
    const canvas = document.createElement('canvas'); canvas.width = 320; canvas.height = 440; canvas.style.width = '100%'; card.append(canvas); document.querySelector('main').append(card);
    const gl = canvas.getContext('webgl', { preserveDrawingBuffer: true }); if (!gl) throw new Error('WebGL context unavailable');
    const compile = (kind, source) => { const shader = gl.createShader(kind); gl.shaderSource(shader, source); gl.compileShader(shader); if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(shader)); return shader; };
    const program = gl.createProgram(); gl.attachShader(program, compile(gl.VERTEX_SHADER, vertex)); gl.attachShader(program, compile(gl.FRAGMENT_SHADER, fragment)); gl.linkProgram(program);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program)); gl.useProgram(program);
    const buffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buffer); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,1,-1,-1,1,-1,1,1,-1,1,1]), gl.STATIC_DRAW);
    const pos = gl.getAttribLocation(program, 'position'); gl.enableVertexAttribArray(pos); gl.vertexAttribPointer(pos,2,gl.FLOAT,false,0,0);
    const loc = name => gl.getUniformLocation(program,name);
    gl.uniform2f(loc('u_resolution'),320,440); gl.uniform2f(loc('u_touch'),0,0); gl.uniform2f(loc('u_hit'),0,0); gl.uniform1f(loc('u_pulseAge'),10); gl.uniform1f(loc('u_mode'),index); gl.uniform1f(loc('u_intensity'),1); gl.uniform1f(loc('u_time'),0); gl.uniform1f(loc('u_quality'),2);
    gl.uniform3f(loc('u_accent'),248/255,211/255,74/255); gl.uniform4f(loc('u_audio'),0,0,0,0); gl.uniform1fv(loc('u_bands'),new Float32Array(32));
    const texture = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D,texture); gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,1,1,0,gl.RGBA,gl.UNSIGNED_BYTE,new Uint8Array([12,14,18,255]));
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR); gl.uniform1i(loc('u_cover'),0); gl.uniform1f(loc('u_hasCover'),0);
    gl.drawArrays(gl.TRIANGLES,0,6); const error = gl.getError(); if(error) throw new Error(`GL error ${error}`);
    const pixels = new Uint8Array(320*440*4); gl.readPixels(0,0,320,440,gl.RGBA,gl.UNSIGNED_BYTE,pixels);
    let energy=0; for(let i=0;i<pixels.length;i+=4) energy+=pixels[i]+pixels[i+1]+pixels[i+2];
    if(!energy) throw new Error(`${name} rendered an empty frame`);
    const capture=()=>{gl.drawArrays(gl.TRIANGLES,0,6);const next=new Uint8Array(pixels.length);gl.readPixels(0,0,320,440,gl.RGBA,gl.UNSIGNED_BYTE,next);return next;};
    const difference=next=>next.reduce((sum,value,i)=>sum+Math.abs(value-pixels[i]),0);
    gl.uniform1f(loc('u_intensity'),0);gl.uniform4f(loc('u_audio'),.8,.6,.4,.5);gl.uniform1fv(loc('u_bands'),new Float32Array(32).fill(.8));
    if(difference(capture())!==0)throw new Error(`${name}: zero intensity still reacts to audio`);
    gl.uniform1f(loc('u_intensity'),1);gl.uniform4f(loc('u_audio'),0,0,0,0);gl.uniform1fv(loc('u_bands'),new Float32Array(32));
    const variants=[];
    for(const quality of [0,1,2])for(const resolution of [[320,440],[440,320]]){
      canvas.width=resolution[0];canvas.height=resolution[1];gl.viewport(0,0,...resolution);
      gl.uniform1f(loc('u_quality'),quality);gl.uniform2f(loc('u_resolution'),...resolution);
      const sample=capture();if(!sample.some((value,i)=>i%4!==3&&value>8))throw new Error(`${name}: blank quality/orientation variant`);
      variants.push({quality,resolution,rendered:true});
    }
    canvas.width=320;canvas.height=440;gl.viewport(0,0,320,440);
    gl.uniform1f(loc('u_quality'),2);gl.uniform2f(loc('u_resolution'),320,440);
    gl.uniform2f(loc('u_touch'),1,-1);
    if(difference(capture())!==0)throw new Error(`${name}: dragging moved the model`);
    gl.uniform1f(loc('u_pulseAge'),.12);const clickDifference=difference(capture());
    if(clickDifference!==0)throw new Error(`${name}: removed click effect still changes the scene`);
    gl.uniform1f(loc('u_pulseAge'),10);gl.uniform4f(loc('u_audio'),.8,0,0,0);
    const bassDifference=difference(capture());
    if(index===0&&bassDifference!==0)throw new Error('Black Hole still changes size or shape from bass');
    if(index!==0&&bassDifference<energy*.01)throw new Error(`${name}: bass response is too weak`);
    gl.uniform4f(loc('u_audio'),0,0,0,0);gl.uniform1f(loc('u_flash'),1);
    const flashDifference=difference(capture());
    if(index===0&&flashDifference<energy*.01)throw new Error('Black Hole flash is not visible');
    gl.uniform1f(loc('u_flash'),index===0?.6:0);
    gl.uniform4f(loc('u_audio'),.7,.4,.3,.5);gl.uniform1fv(loc('u_bands'),new Float32Array(32).fill(.4));
    const audioDifference=difference(capture());if(!audioDifference)throw new Error(`${name}: audio did not change the frame`);
    gl.uniform1f(loc('u_flash'),0);gl.uniform4f(loc('u_audio'),0,0,0,0);gl.uniform1fv(loc('u_bands'),new Float32Array(32));capture();
    gl.uniform3f(loc('u_accent'),1,1,1);const monochrome=capture();
    for(let i=0;i<monochrome.length;i+=4)if(Math.abs(monochrome[i]-monochrome[i+1])+Math.abs(monochrome[i+1]-monochrome[i+2])>2)throw new Error(`${name}: Monochrome contains colored reflections`);
    gl.uniform3f(loc('u_accent'),248/255,211/255,74/255);capture();
    return { mode:name, compiled:true, zeroInputPixelEnergy:energy, stableOnDrag:true, clickDifference, bassDifference, flashDifference, fixedBlackHoleSize:index===0, audioDifference, monochrome:true, zeroIntensity:true, variants };
  }), { fragment:fragmentShader, vertex:vertexShader, modes:visualModes });
  const destination = path.resolve(process.argv[4]); await fs.mkdir(path.dirname(destination),{recursive:true}); await page.screenshot({ path:destination, fullPage:true });
  await fs.writeFile(destination.replace(/\.png$/,'.json'),JSON.stringify(result,null,2)); console.log(JSON.stringify(result));
} finally { await browser.close(); }
