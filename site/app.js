const form = document.querySelector("#search-form");
const input = document.querySelector("#search-input");
const results = document.querySelector("#results");
const resultCount = document.querySelector("#result-count");
const downloadDialog = document.querySelector("#download-dialog");
const downloadForm = document.querySelector("#download-form");
const downloadStatus = document.querySelector("#download-status");
const downloadLink = document.querySelector("#download-link");
const downloadButton = document.querySelector("#start-download");
const rightsCheckbox = document.querySelector("#rights-confirmed");
const config = window.LUCY_CONFIG ?? {};

let activeAudio = null;
let activeButton = null;
let pendingTrack = null;
let turnstileWidget = null;
let turnstileLoadPromise = null;

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const term = input.value.trim();
  if (!term) return;

  stopPreview();
  results.setAttribute("aria-busy", "true");
  results.replaceChildren(makeMessage("Searching the catalogue...", "loading-state"));
  resultCount.textContent = "";

  const url = new URL("https://itunes.apple.com/search");
  url.search = new URLSearchParams({ term, entity: "song", limit: "24" });

  try {
    const response = await fetch(url);
    if (!response.ok) throw new Error("The search request failed.");

    const data = await response.json();
    renderResults(data.results ?? []);
  } catch {
    results.replaceChildren(makeMessage("Search failed. Check your connection and try again."));
  } finally {
    results.setAttribute("aria-busy", "false");
  }
});

downloadForm.addEventListener("submit", handleDownloadSubmit);
document.querySelector("#close-download").addEventListener("click", () => downloadDialog.close());

function renderResults(tracks) {
  resultCount.textContent = tracks.length ? `${tracks.length} ${tracks.length === 1 ? "result" : "results"}` : "";

  if (!tracks.length) {
    results.replaceChildren(makeMessage("No recordings found. Try another search."));
    return;
  }

  const fragment = document.createDocumentFragment();
  tracks.forEach((track, index) => fragment.append(createTrack(track, index)));
  results.replaceChildren(fragment);
}

function createTrack(track, index) {
  const item = document.createElement("article");
  item.className = "track";
  item.style.animationDelay = `${Math.min(index * 25, 250)}ms`;

  const artwork = document.createElement("img");
  artwork.className = "artwork";
  artwork.src = track.artworkUrl100 ?? "";
  artwork.alt = track.collectionName ? `Album artwork for ${track.collectionName}` : "Album artwork";
  artwork.loading = "lazy";

  const copy = document.createElement("div");
  copy.className = "track-copy";
  copy.append(
    textElement("h3", "track-title", track.trackName || "Untitled track"),
    textElement("p", "track-artist", track.artistName || "Unknown artist"),
    textElement("p", "track-album", track.collectionName || "")
  );

  const actions = document.createElement("div");
  actions.className = "track-actions";
  actions.append(createTrackIdButton(track.trackId));
  actions.append(createPreviewButton(track.previewUrl));
  actions.append(createDownloadButton(track));

  if (track.trackViewUrl) {
    const storeLink = document.createElement("a");
    storeLink.className = "store-link";
    storeLink.href = track.trackViewUrl;
    storeLink.target = "_blank";
    storeLink.rel = "noopener noreferrer";
    storeLink.textContent = "Open in Apple Music";
    storeLink.setAttribute("aria-label", `Open ${track.trackName} in Apple Music`);
    actions.append(storeLink);
  }

  item.append(artwork, copy, actions);
  return item;
}

function createTrackIdButton(trackId) {
  const button = document.createElement("button");
  button.className = "icon-button";
  button.type = "button";
  button.innerHTML = copyIcon();
  button.setAttribute("aria-label", "Copy iTunes track ID");
  button.title = "Copy iTunes track ID";
  button.disabled = !trackId;
  button.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(String(trackId));
      button.title = "Track ID copied";
      button.setAttribute("aria-label", "Track ID copied");
    } catch {
      button.title = `iTunes track ID: ${trackId}`;
    }
  });
  return button;
}

function createDownloadButton(track) {
  const button = document.createElement("button");
  button.className = "icon-button download-track-button";
  button.type = "button";
  button.innerHTML = downloadIcon();
  button.setAttribute("aria-label", `Prepare ${track.trackName || "this recording"}`);
  button.title = isServiceConfigured() ? "Prepare authorized audio" : "Configure the Worker API and Turnstile first";
  button.disabled = !track.trackId || !isServiceConfigured();
  button.addEventListener("click", () => openDownloadDialog(track));
  return button;
}

function isServiceConfigured() {
  return typeof config.apiBaseUrl === "string"
    && config.apiBaseUrl.startsWith("https://")
    && !config.apiBaseUrl.includes("YOUR-")
    && typeof config.turnstileSiteKey === "string"
    && config.turnstileSiteKey.length > 10
    && !config.turnstileSiteKey.includes("REPLACE_");
}

function openDownloadDialog(track) {
  pendingTrack = track;
  document.querySelector("#download-track-name").textContent = `${track.trackName} · ${track.artistName}`;
  downloadStatus.textContent = "";
  downloadLink.hidden = true;
  downloadLink.removeAttribute("href");
  rightsCheckbox.checked = false;
  downloadButton.disabled = false;
  downloadDialog.showModal();
  prepareTurnstile();
}

async function prepareTurnstile() {
  if (!isServiceConfigured()) {
    downloadStatus.textContent = "The audio service has not been configured yet.";
    downloadButton.disabled = true;
    return;
  }

  try {
    await loadTurnstile();
    if (turnstileWidget === null) {
      turnstileWidget = window.turnstile.render("#turnstile-container", {
        sitekey: config.turnstileSiteKey,
        callback: () => { downloadStatus.textContent = "Security check complete."; },
        "expired-callback": () => { downloadStatus.textContent = "Security check expired. Please complete it again."; },
        "error-callback": () => { downloadStatus.textContent = "Security check failed to load."; },
      });
    } else {
      window.turnstile.reset(turnstileWidget);
    }
  } catch {
    turnstileLoadPromise = null;
    turnstileWidget = null;
    downloadStatus.textContent = "Could not load the security check.";
    downloadButton.disabled = true;
  }
}

function loadTurnstile() {
  if (window.turnstile) return Promise.resolve();
  if (turnstileLoadPromise) return turnstileLoadPromise;

  turnstileLoadPromise = new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
    script.async = true;
    script.onload = resolve;
    script.onerror = reject;
    document.head.append(script);
  });
  return turnstileLoadPromise;
}

async function handleDownloadSubmit(event) {
  event.preventDefault();
  if (!pendingTrack || !rightsCheckbox.checked) {
    downloadStatus.textContent = "Confirm that you have the required rights to continue.";
    return;
  }

  const token = window.turnstile?.getResponse(turnstileWidget);
  if (!token) {
    downloadStatus.textContent = "Complete the security check first.";
    return;
  }

  downloadButton.disabled = true;
  downloadStatus.textContent = "Submitting request...";
  try {
    const response = await fetch(`${config.apiBaseUrl.replace(/\/$/, "")}/api/jobs`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        track_id: String(pendingTrack.trackId),
        quality: document.querySelector("#download-quality").value,
        rights_confirmed: true,
        turnstile_token: token,
      }),
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || "Could not submit the request.");

    window.turnstile.reset(turnstileWidget);
    downloadStatus.textContent = "Request received. Searching and preparing the file...";
    await pollJob(result.request_id);
  } catch (error) {
    if (window.turnstile && turnstileWidget !== null) window.turnstile.reset(turnstileWidget);
    downloadStatus.textContent = error instanceof Error ? error.message : "Could not connect to the audio service.";
  } finally {
    downloadButton.disabled = false;
  }
}

async function pollJob(requestId) {
  const baseUrl = config.apiBaseUrl.replace(/\/$/, "");
  for (let attempt = 0; attempt < 300; attempt += 1) {
    await new Promise((resolve) => window.setTimeout(resolve, 5000));
    const response = await fetch(`${baseUrl}/api/jobs/${encodeURIComponent(requestId)}`, { cache: "no-store" });
    const job = await response.json();
    if (!response.ok) throw new Error(job.error || "Could not retrieve request status.");
    if (job.status === "ready" && job.download_url) {
      downloadLink.href = job.download_url;
      downloadLink.hidden = false;
      downloadStatus.textContent = "Your file is ready.";
      return;
    }
    if (job.status === "failed") throw new Error(job.error || "The file could not be prepared.");
    if (attempt % 6 === 5) downloadStatus.textContent = "Your request is still processing...";
  }
  downloadStatus.textContent = "Processing is taking longer than expected. Check this request again shortly.";
}

function createPreviewButton(previewUrl) {
  const button = document.createElement("button");
  button.className = "icon-button";
  button.type = "button";
  button.innerHTML = playIcon();
  button.setAttribute("aria-label", previewUrl ? "Play preview" : "Preview unavailable");
  button.title = previewUrl ? "Play preview" : "Preview unavailable";
  button.disabled = !previewUrl;

  if (previewUrl) {
    button.addEventListener("click", () => togglePreview(previewUrl, button));
  }
  return button;
}

function togglePreview(url, button) {
  if (activeButton === button && activeAudio && !activeAudio.paused) {
    activeAudio.pause();
    button.innerHTML = playIcon();
    button.setAttribute("aria-label", "Play preview");
    activeAudio = null;
    activeButton = null;
    return;
  }

  stopPreview();
  const audio = new Audio(url);
  activeAudio = audio;
  activeButton = button;
  button.innerHTML = pauseIcon();
  button.setAttribute("aria-label", "Pause preview");
  audio.addEventListener("ended", stopPreview, { once: true });
  audio.addEventListener("error", stopPreview, { once: true });
  audio.play().catch(stopPreview);
}

function stopPreview() {
  if (activeAudio) {
    activeAudio.pause();
    activeAudio.currentTime = 0;
  }
  if (activeButton) {
    activeButton.innerHTML = playIcon();
    activeButton.setAttribute("aria-label", "Play preview");
  }
  activeAudio = null;
  activeButton = null;
}

function textElement(tag, className, text) {
  const element = document.createElement(tag);
  element.className = className;
  element.textContent = text;
  return element;
}

function makeMessage(message, className = "empty-state") {
  const state = document.createElement("div");
  state.className = className;
  state.append(textElement("p", "", message));
  return state;
}

function playIcon() {
  return '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="m9 6 10 6-10 6V6Z" fill="currentColor"/></svg>';
}

function pauseIcon() {
  return '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M8 6h3v12H8zM14 6h3v12h-3z" fill="currentColor"/></svg>';
}

function copyIcon() {
  return '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="8" y="8" width="11" height="12" rx="2" stroke="currentColor" stroke-width="1.7"/><path d="M16 8V6a2 2 0 0 0-2-2H7a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h1" stroke="currentColor" stroke-width="1.7"/></svg>';
}

function downloadIcon() {
  return '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 3v11m0 0 4-4m-4 4-4-4M5 17v3h14v-3" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>';
}