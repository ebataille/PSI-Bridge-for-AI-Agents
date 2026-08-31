import { CartItem, computeTotal } from './cart';

// Erreur de type volontaire : computeTotal renvoie un number.
export const label: string = computeTotal([]);

export function describe(items: CartItem[]): string {
  const jamaisUtilise = 42;
  return `total ${computeTotal(items)}`;
}
