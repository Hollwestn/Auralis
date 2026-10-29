export type Pairing = { v:1;host:string;port:number;certificate:string;secret:string;expires:string };
export function decodePairing(text:string,now=Date.now()):Pairing {
 if(!text.startsWith('auralis-pair:')||text.length>4096)throw new Error('Нужен QR-код временного сеанса Auralis на компьютере.');
 const alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/',value=text.slice(13).replace(/-/g,'+').replace(/_/g,'/');let bits=0,count=0,out='';
 if(!/^[A-Za-z0-9+/]+={0,2}$/.test(value))throw new Error('Повреждён код подключения.');
 for(const char of value.replace(/=+$/,'')){bits=(bits<<6)|alphabet.indexOf(char);count+=6;if(count>=8){count-=8;out+=String.fromCharCode((bits>>count)&255);}}
 let info:Pairing;try{info=JSON.parse(out) as Pairing;}catch{throw new Error('Повреждён код подключения.');}
 const parts=typeof info.host==='string'?info.host.split('.').map(Number):[];
 const local=parts.length===4&&parts.every(n=>Number.isInteger(n)&&n>=0&&n<=255)&&parts.join('.')===info.host&&(parts[0]===10||parts[0]===192&&parts[1]===168||parts[0]===172&&parts[1]>=16&&parts[1]<=31);
 if(info.v!==1||!local||!Number.isInteger(info.port)||info.port<1||info.port>65535||typeof info.certificate!=='string'||!/^[a-f0-9]{64}$/.test(info.certificate)||typeof info.secret!=='string'||!/^[A-Za-z0-9_-]{43}$/.test(info.secret))throw new Error('Поддерживается только защищённый сеанс в общей локальной Wi-Fi-сети.');
 const until=Date.parse(info.expires);if(!Number.isFinite(until)||until<=now||until>now+1_000_000)throw new Error('Сеанс истёк. Создайте новый QR-код и проверьте время на устройствах.');return info;
}
