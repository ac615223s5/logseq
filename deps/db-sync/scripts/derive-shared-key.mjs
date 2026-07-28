#!/usr/bin/env node
// Derive the DB_SYNC_SHARED_KEY value for a given passphrase.
//
// Account-less self-hosted sync: the client signs its JWT with an HMAC key
// derived from the passphrase (HKDF-SHA256, info="auth"); the server verifies
// that signature with DB_SYNC_SHARED_KEY. Set the printed value in the server
// .env. Must match frontend.common.sync-key exactly.
//
// Usage: node scripts/derive-shared-key.mjs "your passphrase"
import crypto from "node:crypto";

const passphrase = process.argv[2];
if (!passphrase) {
  console.error('Usage: node scripts/derive-shared-key.mjs "your passphrase"');
  process.exit(1);
}

const SALT = Buffer.from("logseq-selfhost-sync-v1", "utf8");
const b64url = (buf) =>
  Buffer.from(buf).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const authKey = Buffer.from(
  crypto.hkdfSync("sha256", Buffer.from(passphrase, "utf8"), SALT, Buffer.from("auth", "utf8"), 32),
);

console.log(b64url(authKey));
