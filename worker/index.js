const QUALITY_OPTIONS = new Set(["128", "192", "256", "320"]);
const TRACK_ID_PATTERN = /^[0-9]{1,20}$/;
const REQUEST_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const JOB_TTL_SECONDS = 60 * 60 * 24;
const MAX_REQUEST_BYTES = 8 * 1024;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const origin = request.headers.get("Origin");

    if (url.pathname.startsWith("/api/internal/")) {
      return handleInternalRequest(request, url, env);
    }

    const cors = corsHeaders(origin, env.ALLOWED_ORIGIN);
    if (!cors) return jsonResponse({ error: "Origin not allowed." }, 403);
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: cors });
    }

    try {
      if (url.pathname === "/api/jobs" && request.method === "POST") {
        return await createJob(request, env, cors);
      }

      const jobMatch = url.pathname.match(/^\/api\/jobs\/([0-9a-f-]{36})$/i);
      if (jobMatch && request.method === "GET") {
        return await getJob(jobMatch[1], env, cors);
      }

      return jsonResponse({ error: "Not found." }, 404, cors);
    } catch {
      return jsonResponse({ error: "The request could not be completed." }, 500, cors);
    }
  },
};

async function createJob(request, env, cors) {
  const contentLength = Number(request.headers.get("Content-Length"));
  if (Number.isFinite(contentLength) && contentLength > MAX_REQUEST_BYTES) {
    return jsonResponse({ error: "Request is too large." }, 413, cors);
  }

  const clientIp = request.headers.get("CF-Connecting-IP");
  if (!clientIp) return jsonResponse({ error: "Client IP is unavailable." }, 400, cors);
  const limit = await env.REQUEST_LIMITER.limit({ key: clientIp });
  if (!limit.success) return jsonResponse({ error: "Too many requests. Try again later." }, 429, cors);

  let body;
  try {
    body = await readLimitedBody(request, MAX_REQUEST_BYTES);
  } catch {
    return jsonResponse({ error: "Invalid request body." }, 400, cors);
  }
  if (body === null) return jsonResponse({ error: "Request is too large." }, 413, cors);

  let payload;
  try {
    payload = JSON.parse(body);
  } catch {
    return jsonResponse({ error: "Invalid JSON." }, 400, cors);
  }

  const trackId = String(payload.track_id ?? "");
  const quality = String(payload.quality ?? "");
  const token = String(payload.turnstile_token ?? "");
  if (!TRACK_ID_PATTERN.test(trackId) || !QUALITY_OPTIONS.has(quality)) {
    return jsonResponse({ error: "Invalid track or quality." }, 400, cors);
  }
  if (payload.rights_confirmed !== true) {
    return jsonResponse({ error: "Confirm that you have the necessary rights." }, 400, cors);
  }
  if (!token || token.length > 2048) {
    return jsonResponse({ error: "Complete the security check and try again." }, 400, cors);
  }

  const turnstile = await verifyTurnstile(token, clientIp, env);
  if (!turnstile.success || turnstile.hostname !== env.TURNSTILE_HOSTNAME) {
    return jsonResponse({ error: "Security check failed. Refresh it and try again." }, 403, cors);
  }

  const requestId = crypto.randomUUID();
  const job = {
    request_id: requestId,
    track_id: trackId,
    quality,
    status: "queued",
    created_at: new Date().toISOString(),
  };
  await env.JOBS.put(jobKey(requestId), JSON.stringify(job), { expirationTtl: JOB_TTL_SECONDS });

  try {
    await dispatchWorkflow(job, env);
  } catch {
    job.status = "failed";
    job.error = "Could not start the download job.";
    await env.JOBS.put(jobKey(requestId), JSON.stringify(job), { expirationTtl: JOB_TTL_SECONDS });
    return jsonResponse({ error: job.error }, 502, cors);
  }

  return jsonResponse({ request_id: requestId, status: job.status }, 202, cors);
}

async function getJob(requestId, env, cors) {
  const record = await env.JOBS.get(jobKey(requestId));
  if (!record) return jsonResponse({ error: "Job not found or expired." }, 404, cors);

  const job = JSON.parse(record);
  return jsonResponse({
    request_id: job.request_id,
    status: job.status,
    download_url: job.download_url ?? null,
    error: job.error ?? null,
    created_at: job.created_at,
  }, 200, cors);
}

async function handleInternalRequest(request, url, env) {
  if (request.method !== "POST") return jsonResponse({ error: "Method not allowed." }, 405);
  if (!env.CALLBACK_TOKEN || !constantTimeEqual(request.headers.get("Authorization") ?? "", `Bearer ${env.CALLBACK_TOKEN}`)) {
    return jsonResponse({ error: "Unauthorized." }, 401);
  }

  const match = url.pathname.match(/^\/api\/internal\/jobs\/([0-9a-f-]{36})$/i);
  if (!match) return jsonResponse({ error: "Not found." }, 404);

  let payload;
  try {
    payload = await request.json();
  } catch {
    return jsonResponse({ error: "Invalid JSON." }, 400);
  }
  if (!["ready", "failed"].includes(payload.status)) {
    return jsonResponse({ error: "Invalid status." }, 400);
  }

  const requestId = match[1];
  const key = jobKey(requestId);
  const record = await env.JOBS.get(key);
  if (!record) return jsonResponse({ error: "Job not found." }, 404);

  const job = JSON.parse(record);
  job.status = payload.status;
  if (payload.status === "ready") {
    const downloadUrl = validateReleaseUrl(payload.download_url, requestId, env);
    if (!downloadUrl) return jsonResponse({ error: "Invalid release URL." }, 400);
    job.download_url = downloadUrl;
  } else {
    job.error = "The download could not be completed.";
  }
  await env.JOBS.put(key, JSON.stringify(job), { expirationTtl: JOB_TTL_SECONDS });
  return jsonResponse({ ok: true }, 200);
}

async function verifyTurnstile(token, clientIp, env) {
  const formData = new URLSearchParams({
    secret: env.TURNSTILE_SECRET_KEY,
    response: token,
    remoteip: clientIp,
  });
  const response = await fetch("https://challenges.cloudflare.com/turnstile/v0/siteverify", {
    method: "POST",
    body: formData,
  });
  if (!response.ok) return { success: false };
  return response.json();
}

async function readLimitedBody(request, limit) {
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > limit) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }

  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
}

async function dispatchWorkflow(job, env) {
  const token = await createInstallationToken(env);
  const endpoint = `https://api.github.com/repos/${encodeURIComponent(env.GITHUB_OWNER)}/${encodeURIComponent(env.GITHUB_REPO)}/actions/workflows/${encodeURIComponent(env.GITHUB_WORKFLOW)}/dispatches`;
  const response = await fetch(endpoint, {
    method: "POST",
    headers: githubHeaders(token),
    body: JSON.stringify({
      ref: env.GITHUB_REF,
      inputs: {
        track_id: job.track_id,
        quality: job.quality,
        request_id: job.request_id,
      },
    }),
  });
  if (response.status !== 204) throw new Error("GitHub workflow dispatch failed.");
}

async function createInstallationToken(env) {
  const now = Math.floor(Date.now() / 1000);
  const header = encodeBase64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = encodeBase64Url(JSON.stringify({
    iat: now - 30,
    exp: now + 8 * 60,
    iss: env.GITHUB_APP_ID,
  }));
  const unsignedToken = `${header}.${claims}`;
  const privateKey = await importPrivateKey(env.GITHUB_PRIVATE_KEY);
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    privateKey,
    new TextEncoder().encode(unsignedToken),
  );
  const appJwt = `${unsignedToken}.${encodeBytesBase64Url(new Uint8Array(signature))}`;
  const endpoint = `https://api.github.com/app/installations/${encodeURIComponent(env.GITHUB_INSTALLATION_ID)}/access_tokens`;
  const response = await fetch(endpoint, {
    method: "POST",
    headers: githubHeaders(appJwt),
    body: JSON.stringify({ permissions: { actions: "write" } }),
  });
  if (!response.ok) throw new Error("Could not create GitHub installation token.");
  const data = await response.json();
  if (!data.token) throw new Error("GitHub returned no installation token.");
  return data.token;
}

async function importPrivateKey(pem) {
  const isPkcs1 = pem.includes("-----BEGIN RSA PRIVATE KEY-----");
  const base64 = pem
    .replace(/-----BEGIN (?:RSA )?PRIVATE KEY-----/g, "")
    .replace(/-----END (?:RSA )?PRIVATE KEY-----/g, "")
    .replace(/\\n/g, "")
    .replace(/\s/g, "");
  const binary = atob(base64);
  let bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  if (isPkcs1) {
    const rsaAlgorithm = Uint8Array.from([
      0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00,
    ]);
    const version = Uint8Array.from([0x02, 0x01, 0x00]);
    bytes = encodeDer(0x30, concatBytes(version, rsaAlgorithm, encodeDer(0x04, bytes)));
  }
  return crypto.subtle.importKey(
    "pkcs8",
    bytes,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
}

function encodeDer(tag, bytes) {
  let lengthBytes;
  if (bytes.length < 128) {
    lengthBytes = Uint8Array.from([bytes.length]);
  } else {
    const encodedLength = [];
    for (let length = bytes.length; length > 0; length = Math.floor(length / 256)) {
      encodedLength.unshift(length & 0xff);
    }
    lengthBytes = Uint8Array.from([0x80 | encodedLength.length, ...encodedLength]);
  }
  return concatBytes(Uint8Array.from([tag]), lengthBytes, bytes);
}

function concatBytes(...parts) {
  const result = new Uint8Array(parts.reduce((total, part) => total + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.length;
  }
  return result;
}

function githubHeaders(token) {
  return {
    Accept: "application/vnd.github+json",
    Authorization: `Bearer ${token}`,
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "Lucy-Cloudflare-Worker",
    "Content-Type": "application/json",
  };
}

function encodeBase64Url(value) {
  return encodeBytesBase64Url(new TextEncoder().encode(value));
}

function encodeBytesBase64Url(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function constantTimeEqual(left, right) {
  if (left.length !== right.length) return false;
  let mismatch = 0;
  for (let index = 0; index < left.length; index += 1) {
    mismatch |= left.charCodeAt(index) ^ right.charCodeAt(index);
  }
  return mismatch === 0;
}

function validateReleaseUrl(value, requestId, env) {
  try {
    const url = new URL(value);
    const expectedPath = `/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/releases/download/audio-${requestId}/`;
    if (url.protocol !== "https:" || url.hostname !== "github.com" || !url.pathname.startsWith(expectedPath)) {
      return "";
    }
    const assetName = decodeURIComponent(url.pathname.slice(expectedPath.length));
    if (!assetName.toLowerCase().endsWith(".mp3") || assetName.includes("/") || assetName.includes("\\")) {
      return "";
    }
    return url.toString();
  } catch {
    return "";
  }
}

function corsHeaders(origin, allowedOrigin) {
  if (!origin || origin !== allowedOrigin) return null;
  return {
    "Access-Control-Allow-Origin": allowedOrigin,
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Max-Age": "86400",
    Vary: "Origin",
  };
}

function jobKey(requestId) {
  return `job:${requestId}`;
}

function jsonResponse(payload, status, headers = {}) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...headers },
  });
}

export { importPrivateKey };
