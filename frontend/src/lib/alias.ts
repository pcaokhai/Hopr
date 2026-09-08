// Mirrors the shortener-service alias validation (see shortener-service AliasValidator).
export const ALIAS_REGEX = /^[A-Za-z0-9](?!.*[_-]{2})[A-Za-z0-9_-]{1,18}[A-Za-z0-9]$/;

export function isValidAlias(alias: string): boolean {
  return ALIAS_REGEX.test(alias);
}
