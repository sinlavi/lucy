import difflib
import json
import os
import re
import subprocess
import sys
import tempfile
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import yt_dlp


MAX_COVER_BYTES = 10 * 1024 * 1024
MAX_ITUNES_RESPONSE_BYTES = 1024 * 1024
MIN_MATCH_SCORE = 0.68
YOUTUBE_HOSTS = {"music.youtube.com", "www.youtube.com", "youtube.com", "youtu.be"}
NOISE_WORDS = {
    "official", "audio", "video", "lyrics", "lyric", "visualizer",
    "remastered", "remaster", "hd", "4k", "vevo", "topic",
}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


def fail(message):
    print(message, file=sys.stderr)
    raise SystemExit(1)


def validate_url(raw_url, allowed_hosts, label):
    try:
        parsed = urllib.parse.urlsplit(raw_url)
        hostname = (parsed.hostname or "").lower().rstrip(".")
        port = parsed.port
    except ValueError:
        fail(f"Invalid {label} URL.")

    is_apple_artwork_host = hostname == "mzstatic.com" or hostname.endswith(".mzstatic.com")
    if (
        parsed.scheme != "https"
        or not hostname
        or parsed.username
        or parsed.password
        or port not in (None, 443)
        or (hostname not in allowed_hosts and not ("mzstatic.com" in allowed_hosts and is_apple_artwork_host))
    ):
        fail(f"{label} URL must use HTTPS on an approved host.")
    return parsed


def download(url, destination, allowed_hosts, max_bytes, label):
    validate_url(url, allowed_hosts, label)
    request = urllib.request.Request(url, headers={"User-Agent": "LucyAudioProcessor/1.0"})

    try:
        opener = urllib.request.build_opener(NoRedirect())
        with opener.open(request, timeout=60) as response:
            validate_url(response.geturl(), allowed_hosts, label)
            content_length = response.headers.get("Content-Length")
            if content_length and int(content_length) > max_bytes:
                fail(f"{label} exceeds the configured size limit.")

            total = 0
            with destination.open("wb") as output:
                while chunk := response.read(1024 * 1024):
                    total += len(chunk)
                    if total > max_bytes:
                        fail(f"{label} exceeds the configured size limit.")
                    output.write(chunk)
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        fail(f"Could not retrieve the approved {label} URL.")

    if total == 0:
        fail(f"The {label} download was empty.")


def fetch_itunes_track(track_id):
    if not re.fullmatch(r"[0-9]+", track_id):
        fail("Enter a numeric iTunes track ID.")

    url = "https://itunes.apple.com/lookup?" + urllib.parse.urlencode({"id": track_id})
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            payload = response.read(MAX_ITUNES_RESPONSE_BYTES + 1)
        if len(payload) > MAX_ITUNES_RESPONSE_BYTES:
            fail("The iTunes response exceeded the size limit.")
        data = json.loads(payload)
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError):
        fail("Could not retrieve track metadata from the iTunes Lookup API.")

    tracks = data.get("results", [])
    if not tracks or tracks[0].get("wrapperType") != "track":
        fail("iTunes did not return a track for that ID.")
    return tracks[0]


def as_text(value):
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        return as_text(value.get("name", ""))
    if isinstance(value, (list, tuple)):
        return " ".join(part for part in (as_text(item) for item in value) if part)
    return "" if value is None else str(value)


def normalize_text(value):
    value = as_text(value).translate(str.maketrans({"ي": "ی", "ك": "ک", "ۀ": "ه", "ة": "ه"}))
    value = unicodedata.normalize("NFKD", value).casefold()
    value = "".join(char for char in value if not unicodedata.combining(char))
    tokens = re.findall(r"[\w]+", value, flags=re.UNICODE)
    return " ".join(token for token in tokens if token not in NOISE_WORDS)


def text_similarity(left, right):
    left = normalize_text(left)
    right = normalize_text(right)
    if not left or not right:
        return 0.0

    sequence_score = difflib.SequenceMatcher(None, left, right).ratio()
    left_tokens = set(left.split())
    right_tokens = set(right.split())
    token_score = 2 * len(left_tokens & right_tokens) / (len(left_tokens) + len(right_tokens))
    containment_score = 0.96 if left in right or right in left else 0.0
    return max(sequence_score, token_score, containment_score)


def candidate_url(candidate):
    explicit_url = candidate.get("webpage_url") or candidate.get("url")
    if explicit_url:
        if not isinstance(explicit_url, str) or not explicit_url.startswith(("http://", "https://")):
            return ""
        hostname = (urllib.parse.urlsplit(explicit_url).hostname or "").lower()
        return explicit_url if hostname in YOUTUBE_HOSTS else ""

    video_id = candidate.get("id")
    if isinstance(video_id, str) and re.fullmatch(r"[A-Za-z0-9_-]{6,}", video_id):
        return f"https://music.youtube.com/watch?v={urllib.parse.quote(video_id)}"
    return ""


def candidate_channel_id(candidate):
    channel_id = as_text(candidate.get("channel_id") or candidate.get("uploader_id"))
    if channel_id:
        return channel_id
    channel_url = as_text(candidate.get("channel_url") or candidate.get("uploader_url"))
    match = re.search(r"/channel/(UC[A-Za-z0-9_-]+)", channel_url)
    return match.group(1) if match else ""


def score_candidate(track, candidate):
    target_title = as_text(track.get("trackName"))
    target_artist = as_text(track.get("artistName"))
    target_album = as_text(track.get("collectionName"))
    candidate_title = as_text(candidate.get("title"))
    candidate_artist = as_text(
        candidate.get("artists")
        or candidate.get("artist")
        or candidate.get("uploader")
        or candidate.get("channel")
    )
    candidate_album = as_text(candidate.get("album"))

    title_score = text_similarity(target_title, candidate_title)
    weighted_scores = [(0.58, title_score)]
    normalized_artist = normalize_text(target_artist)
    normalized_title = normalize_text(candidate_title)
    if normalized_artist:
        artist_score = text_similarity(target_artist, candidate_artist)
        artist_in_title = normalized_artist in normalized_title
        if artist_score < 0.55 and not artist_in_title:
            return 0.0
        if artist_in_title:
            artist_score = max(artist_score, 0.96)
        weighted_scores.append((0.27, artist_score))

    if target_album and candidate_album:
        weighted_scores.append((0.10, text_similarity(target_album, candidate_album)))

    try:
        expected_duration = float(track.get("trackTimeMillis", 0)) / 1000
        candidate_duration = float(candidate.get("duration", 0))
    except (TypeError, ValueError):
        expected_duration = candidate_duration = 0
    if expected_duration > 0 and candidate_duration > 0:
        tolerance = max(20.0, expected_duration * 0.20)
        duration_score = max(0.0, 1.0 - abs(expected_duration - candidate_duration) / tolerance)
        weighted_scores.append((0.05, duration_score))

    total_weight = sum(weight for weight, _ in weighted_scores)
    return sum(weight * score for weight, score in weighted_scores) / total_weight


def rank_candidates(track, candidates, allowed_channel_ids):
    ranked = []
    for candidate in candidates:
        if candidate_channel_id(candidate) not in allowed_channel_ids:
            continue
        url = candidate_url(candidate)
        if not url or not candidate.get("title"):
            continue
        ranked.append({"candidate": candidate, "url": url, "score": score_candidate(track, candidate)})
    return sorted(ranked, key=lambda match: match["score"], reverse=True)


def search_and_download(track, cookie_file, allowed_channel_ids, work_dir):
    query = " ".join(filter(None, (
        as_text(track.get("trackName")),
        as_text(track.get("artistName")),
        as_text(track.get("collectionName")),
    )))
    search_url = "https://music.youtube.com/search?" + urllib.parse.urlencode({"q": query}) + "#songs"
    common_options = {
        "cookiefile": str(cookie_file),
        "js_runtimes": {"node": {}},
        "quiet": True,
        "no_warnings": True,
        "socket_timeout": 30,
        "retries": 2,
        "fragment_retries": 2,
        "cachedir": False,
    }

    try:
        with yt_dlp.YoutubeDL({**common_options, "extract_flat": "in_playlist"}) as ydl:
            search_result = ydl.extract_info(search_url, download=False)
    except yt_dlp.utils.DownloadError:
        fail("YouTube Music search failed. Check the cookie secret and try again.")

    flat_candidates = (search_result or {}).get("entries") or []
    enriched_candidates = []
    try:
        with yt_dlp.YoutubeDL(common_options) as ydl:
            for candidate in flat_candidates[:10]:
                url = candidate_url(candidate)
                if not url:
                    continue
                try:
                    enriched = ydl.extract_info(url, download=False)
                except yt_dlp.utils.DownloadError:
                    continue
                if isinstance(enriched, dict):
                    enriched_candidates.append(enriched)
    except yt_dlp.utils.DownloadError:
        fail("YouTube Music metadata lookup failed. Check the cookie secret and try again.")

    ranked = rank_candidates(track, enriched_candidates, allowed_channel_ids)
    if not ranked:
        fail("YouTube Music returned no songs from the configured authorized channels.")
    if ranked[0]["score"] < MIN_MATCH_SCORE:
        fail("No YouTube Music result matched the iTunes track closely enough.")

    best_match = ranked[0]
    output_template = str(Path(work_dir) / "youtube-source.%(ext)s")
    download_options = {
        **common_options,
        "format": "bestaudio/best",
        "outtmpl": output_template,
        "noplaylist": True,
    }
    try:
        with yt_dlp.YoutubeDL(download_options) as ydl:
            downloaded_info = ydl.extract_info(best_match["url"], download=True)
            source = Path(ydl.prepare_filename(downloaded_info))
    except yt_dlp.utils.DownloadError:
        fail("The selected YouTube Music result could not be downloaded.")

    if not source.is_file() or source.stat().st_size == 0:
        fail("yt-dlp did not produce an audio file.")
    return best_match, source


def cookie_file_from_environment():
    cookie_path = Path(os.environ.get("YOUTUBE_COOKIES_FILE", ""))
    if not cookie_path.is_file():
        fail("The YouTube cookie secret was not configured.")
    if cookie_path.stat().st_size > 5 * 1024 * 1024:
        fail("The YouTube cookie file exceeds the size limit.")
    try:
        with cookie_path.open(encoding="utf-8") as cookie_file:
            header = cookie_file.readline()
    except (OSError, UnicodeError):
        fail("The YouTube cookie file is unreadable.")
    if "Netscape HTTP Cookie File" not in header:
        fail("The YouTube cookie secret must be a Netscape-format cookies.txt file.")
    return cookie_path


def safe_filename(value):
    normalized = unicodedata.normalize("NFKC", value)
    cleaned = re.sub(r"[^\w]+", "_", normalized, flags=re.UNICODE).strip("_")
    return cleaned[:100] or "licensed_audio"


def main():
    track_id = os.environ.get("ITUNES_TRACK_ID", "").strip()
    track = fetch_itunes_track(track_id)
    title = as_text(track.get("trackName")).strip()
    artist = as_text(track.get("artistName")).strip()
    if not title or not artist:
        fail("The iTunes track is missing its title or artist.")

    quality = os.environ.get("AUDIO_QUALITY", "192")
    if quality not in {"128", "192", "256", "320"}:
        fail("Unsupported audio quality.")

    allowed_channel_ids = {
        channel_id.strip()
        for channel_id in os.environ.get("YOUTUBE_ALLOWED_CHANNEL_IDS", "").split(",")
        if channel_id.strip()
    }
    if not allowed_channel_ids:
        fail("Configure YOUTUBE_ALLOWED_CHANNEL_IDS with channels you are authorized to use.")

    cookie_file = cookie_file_from_environment()
    output_dir = Path("dist")
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / f"{safe_filename(title)}_{safe_filename(artist)}.mp3"

    with tempfile.TemporaryDirectory(prefix="lucy-audio-") as work_dir:
        try:
            match, source = search_and_download(track, cookie_file, allowed_channel_ids, work_dir)
        finally:
            cookie_file.unlink(missing_ok=True)

        command = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source)]
        cover_url = as_text(track.get("artworkUrl100")).strip()
        if cover_url:
            cover = Path(work_dir) / "itunes-cover"
            download(cover_url, cover, {"mzstatic.com"}, MAX_COVER_BYTES, "cover image")
            command.extend(["-i", str(cover), "-map", "0:a:0", "-map", "1:v:0"])
        else:
            command.extend(["-map", "0:a:0"])

        track_number = track.get("trackNumber")
        track_count = track.get("trackCount")
        disc_number = track.get("discNumber")
        disc_count = track.get("discCount")
        metadata = {
            "title": title,
            "artist": artist,
            "album_artist": as_text(track.get("collectionArtistName") or artist),
            "album": as_text(track.get("collectionName")),
            "date": as_text(track.get("releaseDate"))[:10],
            "genre": as_text(track.get("primaryGenreName")),
            "track": f"{track_number}/{track_count}" if track_number and track_count else as_text(track_number),
            "disc": f"{disc_number}/{disc_count}" if disc_number and disc_count else as_text(disc_number),
            "isrc": as_text(track.get("isrc")),
            "publisher": as_text(track.get("copyright")),
        }
        command.extend(["-c:a", "libmp3lame", "-b:a", f"{quality}k", "-id3v2_version", "3"])
        for key, value in metadata.items():
            if value:
                command.extend(["-metadata", f"{key}={value}"])

        if cover_url:
            command.extend([
                "-c:v", "mjpeg",
                "-disposition:v", "attached_pic",
                "-metadata:s:v", "title=Album cover",
                "-metadata:s:v", "comment=Cover (front)",
            ])

        command.extend(["-f", "mp3", str(output)])
        try:
            subprocess.run(command, check=True)
        except (OSError, subprocess.CalledProcessError):
            fail("Audio conversion failed. Check that FFmpeg is installed and the source has an audio stream.")

    if not output.is_file() or output.stat().st_size == 0:
        fail("The output audio file was not created.")

    output_file = os.environ.get("GITHUB_OUTPUT")
    if output_file:
        with open(output_file, "a", encoding="utf-8") as handle:
            handle.write(f"asset_filename={output.name}\n")
            handle.write(f"asset_url=https://github.com/{os.environ['GITHUB_REPOSITORY']}/releases/download/{os.environ['RELEASE_TAG']}/{urllib.parse.quote(output.name)}\n")

    summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_file:
        selected_title = as_text(match["candidate"].get("title"))
        with open(summary_file, "a", encoding="utf-8") as handle:
            handle.write(f"## Best match: {selected_title} ({match['score']:.0%})\n")


if __name__ == "__main__":
    main()