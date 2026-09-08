import { test } from "node:test";
import assert from "node:assert/strict";
import { isValidAlias } from "./alias.ts";

test("accepts valid aliases", () => {
  assert.equal(isValidAlias("abc"), true);
  assert.equal(isValidAlias("my-link_1"), true);
  assert.equal(isValidAlias("a1b2c3d4e5f6g7h8i9"), true);
});

test("rejects invalid aliases", () => {
  assert.equal(isValidAlias("ab"), false); // too short
  assert.equal(isValidAlias("-abc"), false); // must start alnum
  assert.equal(isValidAlias("abc-"), false); // must end alnum
  assert.equal(isValidAlias("a--b"), false); // consecutive dashes
  assert.equal(isValidAlias("a__b"), false); // consecutive underscores
  assert.equal(isValidAlias("a".repeat(25)), false); // too long
});
