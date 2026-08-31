import { CartItem, computeTotal, countItems } from './cart';

export function summarize(items: CartItem[]): string {
  const total = computeTotal(items);
  const count = countItems(items);
  return `${count} articles pour ${total} euros`;
}

export function isFreeShipping(items: CartItem[]): boolean {
  return computeTotal(items) > 50;
}
