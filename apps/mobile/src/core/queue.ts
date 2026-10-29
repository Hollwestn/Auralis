export type QueueState<T> = { items: T[]; index: number };
function valid(index: number, size: number) { if (!Number.isInteger(index) || index < 0 || index >= size) throw new Error('Элемент очереди больше недоступен.'); }
export function moveQueue<T>(state: QueueState<T>, from: number, to: number): QueueState<T> {
  valid(from,state.items.length); valid(to,state.items.length); const items = [...state.items], [item] = items.splice(from,1); items.splice(to,0,item);
  const index = state.index === from ? to : from < state.index && to >= state.index ? state.index-1 : from > state.index && to <= state.index ? state.index+1 : state.index;
  return { items, index };
}
export function insertQueue<T>(state: QueueState<T>, item: T, at: number): QueueState<T> {
  if (!Number.isInteger(at) || at < 0 || at > state.items.length) throw new Error('Некорректное место в очереди.');
  const items = [...state.items]; items.splice(at,0,item); return { items, index: state.items.length ? state.index + (at <= state.index ? 1 : 0) : 0 };
}
export function removeQueue<T>(state: QueueState<T>, at: number): QueueState<T> {
  valid(at,state.items.length); const items = [...state.items]; items.splice(at,1); return { items, index: !items.length ? -1 : at < state.index ? state.index-1 : Math.min(state.index,items.length-1) };
}
