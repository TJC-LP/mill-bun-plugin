import isEven from "is-even";

export function checkEven(n: number): boolean {
  return isEven(n);
}

console.log(`4 is even: ${checkEven(4)}`);
