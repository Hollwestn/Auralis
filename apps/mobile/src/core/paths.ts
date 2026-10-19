/** Hex encoding prevents server/user-controlled values from becoming path separators. */
const encode = (value: string) => Array.from(value, char => char.codePointAt(0)!.toString(16).padStart(6, '0')).join('');
export function accountPath(server: string, userId: string) {
  if (!server || server.length > 2048 || !userId || userId.length > 128) throw new Error('Некорректный идентификатор аккаунта.');
  return [...encode(server).match(/.{1,96}/g)!, 'account', ...encode(userId).match(/.{1,96}/g)!];
}
export function trackFilename(id: string, extension: string) {
  if (!/^[a-zA-Z0-9_-]{1,100}$/.test(id)) throw new Error('Некорректный идентификатор трека.');
  return `${id}.${extension}`;
}
