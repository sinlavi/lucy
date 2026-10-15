import assert from "node:assert/strict";
import { generateKeyPairSync, verify } from "node:crypto";
import test from "node:test";

import worker, { importPrivateKey } from "./index.js";

function createEnv() {
  const jobs = new Map();
  const { privateKey } = generateKeyPairSync("rsa", {
    modulusLength: 2048,
    privateKeyEncoding: { type: "pkcs1", format: "pem" },
    publicKeyEncoding: { type: "spki", format: "pem" },
  });
  return {
    jobs,
    env: {
      ALLOWED_ORIGIN: "https://sinlavi.github.io",
      TURNSTILE_HOSTNAME: "sinlavi.github.io",
      TURNSTILE_SECRET_KEY: "turnstile-test-secret",
      GITHUB_OWNER: "sinlavi",
      GITHUB_REPO: "lucy",
      GITHUB_WORKFLOW: "process-authorized-audio.yml",
      GITHUB_REF: "main",
      GITHUB_APP_ID: "123456",
      GITHUB_INSTALLATION_ID: "987654",
      GITHUB_PRIVATE_KEY: privateKey,
      CALLBACK_TOKEN: "callback-test-secret",
      JOBS: {
        get: async (key) => jobs.get(key) ?? null,
        put: async (key, value) => jobs.set(key, value),
      },
      REQUEST_LIMITER: { limit: async () => ({ success: true }) },
    },
  };
}

test("imports the PKCS#1 PEM format used by GitHub App downloads", async () => {
  const { privateKey, publicKey } = generateKeyPairSync("rsa", {
    modulusLength: 2048,
    privateKeyEncoding: { type: "pkcs1", format: "pem" },
    publicKeyEncoding: { type: "spki", format: "pem" },
  });

  const imported = await importPrivateKey(privateKey);

  assert.equal(imported.type, "private");
  assert.equal(imported.algorithm.name, "RSASSA-PKCS1-v1_5");

  const message = new TextEncoder().encode("github-app-jwt-test");
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", imported, message);
  assert.equal(verify("RSA-SHA256", message, publicKey, Buffer.from(signature)), true);
});

test("rejects browser requests from an unconfigured origin", async () => {
  const { env } = createEnv();
  const response = await worker.fetch(new Request("https://api.example/api/jobs", {
    method: "POST",
    headers: { Origin: "https://attacker.example" },
  }), env);

  assert.equal(response.status, 403);
});

test("requires explicit rights confirmation before contacting external services", async () => {
  const { env, jobs } = createEnv();
  const response = await worker.fetch(new Request("https://api.example/api/jobs", {
    method: "POST",
    headers: {
      Origin: env.ALLOWED_ORIGIN,
      "CF-Connecting-IP": "192.0.2.1",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ track_id: "123456", quality: "192", rights_confirmed: false }),
  }), env);

  assert.equal(response.status, 400);
  assert.equal(jobs.size, 0);
});

test("rejects oversized request bodies", async () => {
  const { env } = createEnv();
  const response = await worker.fetch(new Request("https://api.example/api/jobs", {
    method: "POST",
    headers: {
      Origin: env.ALLOWED_ORIGIN,
      "CF-Connecting-IP": "192.0.2.1",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ data: "x".repeat(9000) }),
  }), env);

  assert.equal(response.status, 413);
});

test("returns a client error for malformed request JSON", async () => {
  const { env } = createEnv();
  const response = await worker.fetch(new Request("https://api.example/api/jobs", {
    method: "POST",
    headers: {
      Origin: env.ALLOWED_ORIGIN,
      "CF-Connecting-IP": "192.0.2.1",
      "Content-Type": "application/json",
    },
    body: new Uint8Array([0xff]),
  }), env);

  assert.equal(response.status, 400);
});

test("enforces the Cloudflare rate-limit binding", async () => {
  const { env } = createEnv();
  env.REQUEST_LIMITER.limit = async () => ({ success: false });
  const response = await worker.fetch(new Request("https://api.example/api/jobs", {
    method: "POST",
    headers: {
      Origin: env.ALLOWED_ORIGIN,
      "CF-Connecting-IP": "192.0.2.1",
    },
  }), env);

  assert.equal(response.status, 429);
});

test("dispatches a validated request, stores it, and reports the authenticated callback", async () => {
  const { env, jobs } = createEnv();
  const originalFetch = globalThis.fetch;
  const dispatchCalls = [];
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input);
    if (url.endsWith("/siteverify")) {
      return Response.json({ success: true, hostname: env.TURNSTILE_HOSTNAME });
    }
    if (url.endsWith("/access_tokens")) {
      return Response.json({ token: "installation-token" });
    }
    if (url.endsWith("/dispatches")) {
      dispatchCalls.push(JSON.parse(init.body));
      return new Response(null, { status: 204 });
    }
    throw new Error(`Unexpected URL: ${url}`);
  };

  try {
    const createResponse = await worker.fetch(new Request("https://api.example/api/jobs", {
      method: "POST",
      headers: {
        Origin: env.ALLOWED_ORIGIN,
        "CF-Connecting-IP": "192.0.2.1",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        track_id: "123456",
        quality: "256",
        rights_confirmed: true,
        turnstile_token: "turnstile-response-token",
      }),
    }), env);
    const created = await createResponse.json();

    assert.equal(createResponse.status, 202);
    assert.equal(dispatchCalls.length, 1);
    assert.equal(dispatchCalls[0].inputs.track_id, "123456");
    assert.equal(dispatchCalls[0].inputs.quality, "256");

    const callbackResponse = await worker.fetch(new Request(`https://api.example/api/internal/jobs/${created.request_id}`, {
      method: "POST",
      headers: { Authorization: "Bearer callback-test-secret", "Content-Type": "application/json" },
      body: JSON.stringify({
        status: "ready",
        download_url: `https://github.com/sinlavi/lucy/releases/download/audio-${created.request_id}/track.mp3`,
      }),
    }), env);
    assert.equal(callbackResponse.status, 200);

    const statusResponse = await worker.fetch(new Request(`https://api.example/api/jobs/${created.request_id}`, {
      headers: { Origin: env.ALLOWED_ORIGIN },
    }), env);
    const status = await statusResponse.json();
    assert.equal(status.status, "ready");
    assert.match(status.download_url, /releases\/download\/audio-/);
    assert.ok(jobs.has(`job:${created.request_id}`));

    const badCallback = await worker.fetch(new Request(`https://api.example/api/internal/jobs/${created.request_id}`, {
      method: "POST",
      headers: { Authorization: "Bearer callback-test-secret", "Content-Type": "application/json" },
      body: JSON.stringify({ status: "ready", download_url: "https://attacker.example/file.mp3" }),
    }), env);
    assert.equal(badCallback.status, 400);
  } finally {
    globalThis.fetch = originalFetch;
  }
});