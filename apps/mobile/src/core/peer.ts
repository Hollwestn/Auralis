import { requireNativeModule } from 'expo';
import { decodePairing, type Pairing } from './peerProtocol';
export async function peerRequest(pairing:Pairing,nonce:string,method:'GET'|'POST',path:string,payload:unknown={},source='',destination=''){
 const module=requireNativeModule('ExpoAudio');if(typeof module.auralisPeerRequest!=='function')throw new Error('Установите новую сборку Auralis для прямого обмена.');
 const result=await module.auralisPeerRequest(JSON.stringify(pairing),method,path,nonce,JSON.stringify(payload),source,destination) as {status:number;body:string;uri?:string};
 if(result.status<200||result.status>=300){const messages:Record<number,string>={401:'Создайте новый сеанс на компьютере.',403:'Подтвердите сопряжение на компьютере.',404:'На компьютере ещё не выбрана музыка или сеанс уже закрыт.',409:'Другая передача ещё выполняется.',410:'Сеанс завершён. Создайте новый QR-код.',413:'Недостаточно места или архив превышает 3 ГБ.'};throw new Error(messages[result.status]||`Не удалось выполнить передачу (${result.status}).`);}
 return JSON.parse(result.body) as {requestId?:string;code?:string;approved?:boolean;ready?:boolean;received?:boolean};
}
export {decodePairing};
