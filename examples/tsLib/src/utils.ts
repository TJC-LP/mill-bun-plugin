/** Greet someone by name. */
export function greet(name: string): string {
  return `Hello, ${name}! Built with Mill + Bun.`;
}

/** Format a Date as YYYY-MM-DD. */
export function formatDate(date: Date): string {
  return date.toISOString().split("T")[0];
}

/** Validate an email address. */
export function isValidEmail(email: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
}

/** Capitalize the first letter of a string. */
export function capitalize(s: string): string {
  return s.length === 0 ? s : s[0].toUpperCase() + s.slice(1);
}
